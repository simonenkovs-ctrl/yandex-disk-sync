package ru.simonenkov.yandexdisksync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import ru.simonenkov.yandexdisksync.ui.screens.FoldersScreen
import ru.simonenkov.yandexdisksync.ui.screens.LogScreen
import ru.simonenkov.yandexdisksync.ui.screens.MainScreen
import ru.simonenkov.yandexdisksync.ui.screens.SettingsScreen
import ru.simonenkov.yandexdisksync.ui.theme.YandexDiskSyncTheme

sealed class Screen(val title: String, val icon: ImageVector) {
    object Main : Screen("Главная", Icons.Default.Sync)
    object Folders : Screen("Папки", Icons.Default.Folder)
    object Log : Screen("Журнал", Icons.Default.ListAlt)
    object Settings : Screen("Настройки", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val app = application as YandexDiskApp
        val preferences = app.preferences
        val syncEngine = app.syncEngine

        setContent {
            YandexDiskSyncTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(currentScreen.title) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(Screen.Main, Screen.Folders, Screen.Log, Screen.Settings)
                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) },
                                    selected = currentScreen == screen,
                                    onClick = { currentScreen = screen }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (currentScreen) {
                            Screen.Main -> MainScreen(
                                preferences = preferences,
                                syncEngine = syncEngine,
                                onNavigateToFolders = { currentScreen = Screen.Folders },
                                onNavigateToSettings = { currentScreen = Screen.Settings }
                            )
                            Screen.Folders -> FoldersScreen(preferences = preferences)
                            Screen.Log -> LogScreen(syncEngine = syncEngine)
                            Screen.Settings -> SettingsScreen(preferences = preferences)
                        }
                    }
                }
            }
        }
    }
}
