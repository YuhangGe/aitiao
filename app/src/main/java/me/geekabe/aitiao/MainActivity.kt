package me.geekabe.aitiao

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.geekabe.aitiao.ui.AppConfigScreen
import me.geekabe.aitiao.ui.AppListScreen
import me.geekabe.aitiao.ui.FingerprintDetailScreen
import me.geekabe.aitiao.ui.LogScreen
import me.geekabe.aitiao.ui.SettingsScreen
import me.geekabe.aitiao.ui.theme.AitiaoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AitiaoTheme {
                var currentScreen by remember { mutableStateOf(Screen.APP_LIST) }
                var selectedPackageName by remember { mutableStateOf("") }
                var selectedAppName by remember { mutableStateOf("") }

                // 屏幕路由
                when (currentScreen) {
                    Screen.APP_LIST -> AppListScreen(
                        onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                        onNavigateToLog = { currentScreen = Screen.LOG },
                        onNavigateToConfig = { currentScreen = Screen.APP_CONFIG },
                        onAppClick = { appInfo ->
                            selectedPackageName = appInfo.packageName
                            selectedAppName = appInfo.appName
                            currentScreen = Screen.FINGERPRINT_DETAIL
                        }
                    )
                    Screen.APP_CONFIG -> AppConfigScreen(
                        onBack = { currentScreen = Screen.APP_LIST }
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        onBack = { currentScreen = Screen.APP_LIST }
                    )
                    Screen.FINGERPRINT_DETAIL -> FingerprintDetailScreen(
                        packageName = selectedPackageName,
                        appName = selectedAppName,
                        onBack = { currentScreen = Screen.APP_LIST }
                    )
                    Screen.LOG -> LogScreen(
                        onBack = { currentScreen = Screen.APP_LIST }
                    )
                }
            }
        }
    }
}

private enum class Screen {
    APP_LIST,
    APP_CONFIG,
    SETTINGS,
    FINGERPRINT_DETAIL,
    LOG
}
