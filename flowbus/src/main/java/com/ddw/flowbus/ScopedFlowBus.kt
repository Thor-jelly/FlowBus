package com.ddw.flowbus

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * 带作用域的事件总线
 *
 * 用于限定事件传递范围（例如只在某个动态模块或子系统内）。
 * 每个模块可以通过继承或单例的方式，构建自己专属的 Bus。
 *
 * 示例：
 * ```
 * object OrderModuleBus : ScopedFlowBus()
 * object UserModuleBus : ScopedFlowBus()
 * ```
 */
open class ScopedFlowBus {

    private val flows = ConcurrentHashMap<String, MutableSharedFlow<Any>>()

    private fun getFlow(key: String): MutableSharedFlow<Any> {
        flows[key]?.let { return it }
        val candidate = MutableSharedFlow<Any>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        // 复用 FlowBus 里的兼容工具：API 24+ 走 computeIfAbsent，API 23 走 putIfAbsent
        return putIfAbsentCompat(flows, key, candidate).first
    }

    /** 发送事件（挂起版本） */
    suspend fun <T : Any> post(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ) {
        getFlow(key).emit(event)
    }

    /** 发送事件（非挂起版本） */
    fun <T : Any> tryPost(
        event: T,
        key: String = event::class.java.canonicalName ?: event::class.java.name
    ): Boolean {
        return getFlow(key).tryEmit(event)
    }

    /** 订阅事件 */
    fun <T : Any> on(
        eventType: Class<T>,
        key: String = eventType.canonicalName ?: eventType.name
    ): Flow<T> {
        return getFlow(key).asSharedFlow()
            .filter { eventType.isInstance(it) }
            .map { eventType.cast(it)!! }
    }

    /** 模块卸载时清空所有事件 */
    fun clear() {
        flows.clear()
    }
}

/** 通过 reified 泛型订阅 ScopedFlowBus 事件 */
inline fun <reified T : Any> ScopedFlowBus.on(): Flow<T> = on(T::class.java)
