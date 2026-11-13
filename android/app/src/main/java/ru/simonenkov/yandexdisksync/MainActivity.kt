package ru.simonenkov.yandexdisksync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ru.simonenkov.yandexdisksync.ui.components.UpdateDialog
import ru.simonenkov.yandexdisksync.ui.screens.FoldersScreen
import ru.simonenkov.yandexdisksync.ui.screens.LogScreen
import ru.simonenkov.yandexdisksync.ui.screens.MainScreen
import ru.simonenkov.yandexdisksync.ui.screens.SettingsScreen
import ru.simonenkov.yandexdisksync.ui.theme.YandexDiskSyncTheme
import ru.simonenkov.yandexdisksync.updater.AppUpdater
import ru.simonenkov.yandexdisksync.updater.UpdateInfo

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

    private val appUpdater = AppUpdater()

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
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            "1.2.3"
        }

        setContent {
            YandexDiskSyncTheme {
                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

                var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
                var isCheckingUpdate by remember { mutableStateOf(false) }
                var isDownloadingUpdate by remember { mutableStateOf(false) }
                var downloadProgress by remember { mutableStateOf(0f) }

                fun triggerCheckUpdates(showNoUpdateToast: Boolean = true) {
                    if (isCheckingUpdate) return
                    isCheckingUpdate = true
                    scope.launch {
                        val result = appUpdater.checkForUpdates(currentVersion)
                        isCheckingUpdate = false
                        result.onSuccess { update ->
                            if (update != null) {
                                availableUpdate = update
                            } else if (showNoUpdateToast) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "У вас установлена актуальная версия ($currentVersion)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }.onFailure { err ->
                            if (showNoUpdateToast) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Ошибка проверки обновлений: ${err.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                // Автоматическая тихая проверка при старте
                LaunchedEffect(Unit) {
                    triggerCheckUpdates(showNoUpdateToast = false)
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(currentScreen.title) },
                            actions = {
                                IconButton(
                                    onClick = { triggerCheckUpdates(showNoUpdateToast = true) },
                                    enabled = !isCheckingUpdate
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = "Обновить приложение"
                                    )
                                }
                            },
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

                // Диалог скачивания и установки обновления
                if (availableUpdate != null) {
                    UpdateDialog(
                        updateInfo = availableUpdate!!,
                        isDownloading = isDownloadingUpdate,
                        downloadProgress = downloadProgress,
                        onDismiss = {
                            if (!isDownloadingUpdate) availableUpdate = null
                        },
                        onStartDownload = {
                            isDownloadingUpdate = true
                            downloadProgress = 0f
                            scope.launch {
                                val dlResult = appUpdater.downloadApk(
                                    context = this@MainActivity,
                                    apkUrl = availableUpdate!!.apkUrl,
                                    targetFileName = availableUpdate!!.apkName,
                                    onProgress = { p -> downloadProgress = p }
                                )
                                isDownloadingUpdate = false
                                dlResult.onSuccess { apkFile ->
                                    availableUpdate = null
                                    appUpdater.installApk(this@MainActivity, apkFile)
                                }.onFailure { err ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Ошибка загрузки обновления: ${err.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
