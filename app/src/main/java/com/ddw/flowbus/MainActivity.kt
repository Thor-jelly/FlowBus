package com.ddw.flowbus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ddw.flowbus.ui.theme.FlowBusTheme
import kotlinx.coroutines.launch

// ==================== 事件定义 ====================
data class LoginSuccessEvent(val userId: String, val userName: String)
data class NetworkChangeEvent(val isConnected: Boolean)
data class ThemeChangeEvent(val isDarkMode: Boolean)
object TokenExpiredEvent

// ==================== ViewModel：演示在 ViewModel 里订阅事件 ====================
class DemoViewModel : ViewModel() {

    init {
        // 在 ViewModel 中订阅，框架已经做了异常隔离
        observeEvent<TokenExpiredEvent> {
            // 例如：清空用户缓存、跳登录页
            MessageHolder.value = "[ViewModel] Token 过期，已清理本地数据"
        }
    }

    fun mockLogin() {
        viewModelScope.launch {
            // 协程内用挂起版本 post
            FlowBus.post(LoginSuccessEvent(userId = "u_001", userName = "吴冬冬"))
        }
    }
}

// ==================== Activity：演示在 Activity 里订阅事件 ====================
class MainActivity : ComponentActivity() {

    private val vm: DemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 普通事件订阅：自动跟随生命周期，业务异常自动捕获
        observeEvent<LoginSuccessEvent> { event ->
            latestMessage = "🟢 收到登录事件：${event.userName} (${event.userId})"
        }

        observeEvent<NetworkChangeEvent> { event ->
            latestMessage = if (event.isConnected) "🟢 网络已连接" else "🔴 网络断开"
        }

        // 粘性事件订阅：进来就能拿到最近一次值
        observeStickyEvent<ThemeChangeEvent> { event ->
            latestMessage = "主题切换为：${if (event.isDarkMode) "深色" else "浅色"}"
        }

        setContent {
            FlowBusTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    DemoScreen(
                        modifier = Modifier.padding(padding),
                        message = latestMessage,
                        onLogin = { vm.mockLogin() },
                        onNetworkOn = { FlowBus.tryPost(NetworkChangeEvent(true)) },
                        onNetworkOff = { FlowBus.tryPost(NetworkChangeEvent(false)) },
                        onToggleTheme = { isDark ->
                            FlowBus.tryPostSticky(ThemeChangeEvent(isDark))
                        },
                        onTokenExpired = { FlowBus.tryPost(TokenExpiredEvent) }
                    )
                }
            }
        }
    }

    // 用 Compose 状态承接最新事件文案，方便在屏幕上看到效果
    private var latestMessage: String
        get() = MessageHolder.value
        set(value) {
            MessageHolder.value = value
        }
}

/** 简单的进程内观察对象，给 Demo 演示用 */
object MessageHolder {
    var value: String by mutableStateOf("等待事件…")
}

@Composable
private fun DemoScreen(
    modifier: Modifier = Modifier,
    message: String,
    onLogin: () -> Unit,
    onNetworkOn: () -> Unit,
    onNetworkOff: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onTokenExpired: () -> Unit
) {
    var isDark by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "FlowBus Demo", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        HorizontalDivider()

        Text(text = "最新事件：")
        Text(text = message)
        HorizontalDivider()

        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
            Text("发送登录成功事件（ViewModel.viewModelScope 内 post）")
        }
        Button(onClick = onNetworkOn, modifier = Modifier.fillMaxWidth()) {
            Text("发送网络已连接事件（tryPost）")
        }
        Button(onClick = onNetworkOff, modifier = Modifier.fillMaxWidth()) {
            Text("发送网络断开事件（tryPost）")
        }
        Button(
            onClick = {
                isDark = !isDark
                onToggleTheme(isDark)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("切换主题（粘性事件 tryPostSticky）")
        }
        Button(onClick = onTokenExpired, modifier = Modifier.fillMaxWidth()) {
            Text("发送 Token 过期（ViewModel 端订阅）")
        }
    }
}
