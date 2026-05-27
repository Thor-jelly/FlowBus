# FlowBus

基于 Kotlin 协程 + Flow 的工业级事件总线，用于替代 RxBus / EventBus。

> 🔥 完整原理与设计思路请移步公众号文章：[Kotlin Flow 完全指南（四）：FlowBus 事件总线封装](https://mp.weixin.qq.com/s/1uKIVXZaDveqYXe6ql7pGA)

## 特性

- 普通事件 + 粘性事件双通道
- 协程生命周期感知，自动取消订阅
- 无订阅者 3 秒防抖自动清理，零内存泄漏
- 业务异常隔离，订阅链路不会被一次崩溃击垮
- 支持 `ScopedFlowBus` 模块级作用域隔离

## 快速使用

### 1. 定义事件

```kotlin
data class LoginSuccessEvent(val userId: String, val userName: String)
data class NetworkChangeEvent(val isConnected: Boolean)
object TokenExpiredEvent
```

### 2. 发送事件

```kotlin
// 协程内（挂起）
viewModelScope.launch {
    FlowBus.post(LoginSuccessEvent(user.id, user.name))
}

// 系统回调等非协程环境（非挂起）
FlowBus.tryPost(NetworkChangeEvent(isConnected = true))

// 粘性事件
FlowBus.tryPostSticky(ThemeChangeEvent(isDarkMode = true))
```

### 3. 订阅事件（推荐用扩展函数）

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Activity / Fragment：自动跟随生命周期
        observeEvent<LoginSuccessEvent> { event ->
            updateUserInfo(event.userName)
        }

        // 粘性事件
        observeStickyEvent<ThemeChangeEvent> { event ->
            applyTheme(event.isDarkMode)
        }
    }
}

// ViewModel
class HomeViewModel : ViewModel() {
    init {
        observeEvent<TokenExpiredEvent> {
            clearUserData()
        }
    }
}
```

### 4. 需要自定义 Flow 操作符时手动收集

```kotlin
lifecycleScope.launch {
    FlowBus.on<NetworkChangeEvent>()
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .debounce(300)
        .collect { event ->
            try {
                showNetworkStatus(event.isConnected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("FlowBus", "处理事件异常", e)
            }
        }
}
```

> ⚠️ 手动 `collect` 时务必自己写 `try/catch` 并重抛 `CancellationException`，否则一次业务异常就会让整条订阅链路死掉。能用 `observeEvent` 就别手动写。

### 5. 模块级作用域 ScopedFlowBus

```kotlin
object OrderModuleBus : ScopedFlowBus()

OrderModuleBus.tryPost(OrderCreatedEvent(orderId))

lifecycleScope.launch {
    OrderModuleBus.on<OrderCreatedEvent>().collect { /* ... */ }
}

// 模块卸载时
OrderModuleBus.clear()
```

### 6. 工具方法

```kotlin
FlowBus.removeSticky<ThemeChangeEvent>()  // 清除指定粘性事件
FlowBus.clear()                           // 清空所有事件（一般在登出/重载时调用）
```

## API 速查

| API | 说明 |
| --- | --- |
| `FlowBus.post(event)` | 协程内发送普通事件 |
| `FlowBus.tryPost(event)` | 非协程环境发送普通事件 |
| `FlowBus.postSticky(event)` / `tryPostSticky(event)` | 发送粘性事件 |
| `FlowBus.on<T>()` / `onSticky<T>()` | 获取事件 Flow |
| `FlowBus.removeSticky<T>()` | 清除指定类型的粘性缓存 |
| `FlowBus.clear()` | 清空全部事件 |
| `LifecycleOwner.observeEvent<T> { }` | Activity / Fragment 安全订阅（含异常保护）|
| `LifecycleOwner.observeStickyEvent<T> { }` | Activity / Fragment 安全订阅粘性事件 |
| `ViewModel.observeEvent<T> { }` | ViewModel 中安全订阅 |
| `ViewModel.observeStickyEvent<T> { }` | ViewModel 中安全订阅粘性事件 |

## RxBus → FlowBus 迁移对照

| RxBus | FlowBus |
| --- | --- |
| `RxBus.post(event)` | `FlowBus.post(event)` / `FlowBus.tryPost(event)` |
| `RxBus.toObservable(Event::class.java).subscribe { }` | `observeEvent<Event> { }` |
| `compositeDisposable.add(...)` | 协程自动管理 |
| `compositeDisposable.clear()` | 不需要，协程取消时自动停止 |

## 关注公众号

更深入的设计解析、踩坑记录、协程系列文章都在公众号：

![关注我的公众号](gzh.png)
