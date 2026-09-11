package com.anythingllm.importer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.anythingllm.importer.data.config.AppConfig
import com.anythingllm.importer.data.config.ThemeMode
import com.anythingllm.importer.ui.navigation.AppNav
import com.anythingllm.importer.ui.theme.AnythingLLMTheme

class MainActivity : ComponentActivity() {

    // Android 13+:同步进度通知权限
    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝时通知不显示,功能不受影响 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = application as AnythingLLMApp
        setContent {
            // 全局统一主题(FR-15 深色模式):由设置页 themeMode 决定,各 Screen 不再单独包裹
            val config by app.configRepository.config.collectAsState(initial = AppConfig.defaults())
            val darkTheme = when (config.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AnythingLLMTheme(darkTheme = darkTheme) {
                AppNav()
            }
        }
    }
}
