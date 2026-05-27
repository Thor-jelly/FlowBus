package com.ddw.flowbus

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "FlowBus"

/**
 * 在 LifecycleOwner 中安全订阅事件
 * 自动在 STARTED 时开始收集，STOPPED 时停止
 * action 内部抛出的业务异常会被拦截并打 Log，订阅链路保持畅通
 */
inline fun <reified T : Any> LifecycleOwner.observeEvent(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline action: (T) -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(minActiveState) {
            FlowBus.on<T>().collect { event ->
                // 🌟 在 collect 最末端给业务代码套上保护层
                try {
                    action(event)
                } catch (e: CancellationException) {
                    throw e // 协程取消异常必须放行，否则破坏取消机制
                } catch (e: Throwable) {
                    // 业务方代码崩了，吞掉异常 + 打 Log，collect 协程依然活着
                    Log.e(
                        "FlowBus",
                        "🚨 [${this@observeEvent::class.java.simpleName}] 处理事件 " +
                                "[${T::class.java.simpleName}] 时发生异常，已拦截，订阅链路畅通",
                        e
                    )
                }
            }
        }
    }
}

/**
 * 在 LifecycleOwner 中安全订阅粘性事件
 */
inline fun <reified T : Any> LifecycleOwner.observeStickyEvent(
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    crossinline action: (T) -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(minActiveState) {
            FlowBus.onSticky<T>().collect { event ->
                try {
                    action(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(
                        "FlowBus",
                        "🚨 [${this@observeStickyEvent::class.java.simpleName}] 处理粘性事件 " +
                                "[${T::class.java.simpleName}] 时发生异常，已拦截",
                        e
                    )
                }
            }
        }
    }
}

/**
 * 在 ViewModel 中订阅事件
 */
inline fun <reified T : Any> ViewModel.observeEvent(
    crossinline action: (T) -> Unit
) {
    viewModelScope.launch {
        FlowBus.on<T>().collect { event ->
            try {
                action(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(
                    "FlowBus",
                    "🚨 [${this@observeEvent::class.java.simpleName}] 处理事件 " +
                            "[${T::class.java.simpleName}] 时发生异常，已拦截",
                    e
                )
            }
        }
    }
}

/**
 * 在 ViewModel 中订阅粘性事件
 */
inline fun <reified T : Any> ViewModel.observeStickyEvent(
    crossinline action: (T) -> Unit
) {
    viewModelScope.launch {
        FlowBus.onSticky<T>().collect { event ->
            try {
                action(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(
                    "FlowBus",
                    "🚨 [${this@observeStickyEvent::class.java.simpleName}] 处理粘性事件 " +
                            "[${T::class.java.simpleName}] 时发生异常，已拦截",
                    e
                )
            }
        }
    }
}
