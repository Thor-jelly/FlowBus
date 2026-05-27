package com.ddw.flowbus

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 工业级全局协程事件总线
 *
 * 设计要点：
 * 1. 用全路径类名做 key，防止不同模块同名类污染
 * 2. 自带"3 秒防抖冷清理"机制，无订阅者时自动释放内存，但绝不误杀新流
 * 3. 严格遵循协程生命周期，避免后台漏电与重复消费
 * 4. 高版本（API 24+）走 computeIfAbsent / computeIfPresent；
 *    低版本（API 23）走 putIfAbsent / remove(key, value) 兜底。两条路径功能等价。
 */
object FlowBus {

    private const val TAG = "FlowBus"

    /** 普通事件总线（非粘性） */
    private val busMap = ConcurrentHashMap<String, MutableSharedFlow<Any>>()

    /** 粘性事件总线（新订阅者能收到最近一次事件） */
    private val stickyBusMap = ConcurrentHashMap<String, MutableSharedFlow<Any>>()

    /** 后台清理协程作用域：用 Default 不占主线程 */
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 获取或创建普通事件 Flow，带防抖式自动清理
     */
    private fun getFlow(key: String): MutableSharedFlow<Any> {
        // 1. 快速通路：已存在直接返回
        busMap[key]?.let { return it }
        // 2. 创建候选 flow
        val candidate = newBusFlow(replay = 0)
        // 3. 原子注册：返回最终留在 map 里的赢家 + 当前线程是否是赢家
        val (winner, wonRace) = putIfAbsentCompat(busMap, key, candidate)
        // 4. 只有赢家的候选才启动清理协程，避免 race 输家残留孤儿协程
        if (wonRace) startCleanup(key, candidate)
        return winner
    }

    /**
     * 获取或创建粘性事件 Flow（粘性 Flow 通常需要长期保留 replay 缓存，不做自动清理）
     */
    private fun getStickyFlow(key: String): MutableSharedFlow<Any> {
        stickyBusMap[key]?.let { return it }
        val candidate = newBusFlow(replay = 1)
        // 粘性场景不需要清理协程，只取赢家即可
        return putIfAbsentCompat(stickyBusMap, key, candidate).first
    }

    /**
     * 启动 3 秒防抖清理协程：订阅数归零并保持 3 秒后从 map 移除该 flow
     */
    private fun startCleanup(key: String, flow: MutableSharedFlow<Any>) {
        cleanupScope.launch {
            // 用 coroutineContext[Job] 拿到自身 Job，避免 lateinit 在并发下未初始化先被读
            val selfJob = coroutineContext[Job]
            flow.subscriptionCount.collectLatest { count ->
                if (count == 0) {
                    delay(3000) // 给配置变更、页面切换留 3 秒重连缓冲
                    if (removeIfMatch(busMap, key, flow)) {
                        // 移除成功后取消自身，否则 collectLatest 会一直挂在已无人订阅的 StateFlow 上
                        selfJob?.cancel()
                    }
                }
            }
        }
    }

    private fun newBusFlow(replay: Int): MutableSharedFlow<Any> = MutableSharedFlow(
        replay = replay,
        extraBufferCapacity = 64,                  // 防洪缓冲区
        onBufferOverflow = BufferOverflow.DROP_OLDEST  // 慢消费者直接丢弃老数据
    )

    // ==================== 发送事件 ====================

    /** 发送普通事件（挂起版本，协程内使用） */
    suspend fun <T : Any> post(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ) {
        getFlow(key).emit(event)
    }

    /** 发送普通事件(非挂起版本，适合系统回调等非协程环境) */
    fun <T : Any> tryPost(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ): Boolean {
        val success = getFlow(key).tryEmit(event)
        if (!success) {
            Log.e(TAG, "🚨 事件 [$key] 发送失败：可能是缓冲区配置冲突")
        }
        return success
    }

    /** 发送粘性事件（挂起版本） */
    suspend fun <T : Any> postSticky(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ) {
        getStickyFlow(key).emit(event)
    }

    /** 发送粘性事件（非挂起版本） */
    fun <T : Any> tryPostSticky(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ): Boolean {
        val success = getStickyFlow(key).tryEmit(event)
        if (!success) {
            Log.e(TAG, "🚨 粘性事件 [$key] 发送失败")
        }
        return success
    }

    // ==================== 订阅事件 ====================

    /** 订阅普通事件 */
    fun <T : Any> on(
        eventType: Class<T>,
        key: String = eventType.canonicalName ?: eventType.name
    ): Flow<T> {
        return getFlow(key).asSharedFlow()
            .filter { eventType.isInstance(it) }
            .map { eventType.cast(it)!! }
    }

    /** 订阅粘性事件 */
    fun <T : Any> onSticky(
        eventType: Class<T>,
        key: String = eventType.canonicalName ?: eventType.name
    ): Flow<T> {
        return getStickyFlow(key).asSharedFlow()
            .filter { eventType.isInstance(it) }
            .map { eventType.cast(it)!! }
    }

    // ==================== 工具方法 ====================

    /** 移除指定类型的粘性事件缓存 */
    fun <T : Any> removeSticky(
        eventType: Class<T>,
        key: String = eventType.canonicalName ?: eventType.name
    ) {
        stickyBusMap[key]?.resetReplayCache()
    }

    /** 清空所有事件（一般在登出或重载时调用） */
    fun clear() {
        // 先把所有清理协程一起取消，再清空 map，防止协程泄漏
        cleanupScope.coroutineContext[Job]?.cancelChildren()
        busMap.clear()
        stickyBusMap.clear()
    }
}

// ==================== ConcurrentMap 兼容工具（按 SDK 分流） ====================

/**
 * 原子地将 candidate 注册到 map：
 * - API 24+：走 computeIfAbsent，lambda 在并发下只会执行一次
 * - API 23 ：走 ConcurrentMap.putIfAbsent（Java 1.5 起可用）
 *
 * 返回 (最终留在 map 里的赢家, 当前调用是否是赢家)
 */
internal fun <K : Any, V : Any> putIfAbsentCompat(
    map: ConcurrentHashMap<K, V>,
    key: K,
    candidate: V
): Pair<V, Boolean> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val winner = map.computeIfAbsent(key) { candidate }
        winner to (winner === candidate)
    } else {
        val existing = map.putIfAbsent(key, candidate)
        if (existing != null) existing to false else candidate to true
    }
}

/**
 * 原子地"等于才移除"：
 * - API 24+：走 computeIfPresent，可在原子块内做更细的双重校验
 * - API 23 ：走 ConcurrentMap.remove(key, value)（Java 1.5 起可用，CAS 语义）
 *
 * 返回 true 表示本次调用真的移除了 (key, expected)。
 */
internal fun <K : Any, V : MutableSharedFlow<Any>> removeIfMatch(
    map: ConcurrentHashMap<K, V>,
    key: K,
    expected: V
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        var removed = false
        map.computeIfPresent(key) { _, current ->
            if (current === expected && expected.subscriptionCount.value == 0) {
                removed = true
                null
            } else {
                current
            }
        }
        removed
    } else {
        // ConcurrentMap.remove(k, v) 是 CAS 语义，等于才移除，不会误杀新 flow
        expected.subscriptionCount.value == 0 && map.remove(key, expected)
    }
}

// ==================== Kotlin 泛型扩展（更优雅的 API） ====================

/** 通过 reified 泛型订阅普通事件 */
inline fun <reified T : Any> FlowBus.on(): Flow<T> = on(T::class.java)

/** 通过 reified 泛型订阅粘性事件 */
inline fun <reified T : Any> FlowBus.onSticky(): Flow<T> = onSticky(T::class.java)

/** 通过 reified 泛型移除粘性事件缓存 */
inline fun <reified T : Any> FlowBus.removeSticky() = removeSticky(T::class.java)
