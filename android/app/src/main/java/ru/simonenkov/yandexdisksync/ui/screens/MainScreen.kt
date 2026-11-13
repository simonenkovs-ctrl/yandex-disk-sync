package ru.simonenkov.yandexdisksync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.simonenkov.yandexdisksync.api.DiskInfo
import ru.simonenkov.yandexdisksync.api.YandexDiskClient
import ru.simonenkov.yandexdisksync.data.AppPreferences
import ru.simonenkov.yandexdisksync.data.SyncFolderPair
import ru.simonenkov.yandexdisksync.engine.SyncEngine
import ru.simonenkov.yandexdisksync.service.SyncForegroundService
import ru.simonenkov.yandexdisksync.ui.theme.SuccessGreen
import ru.simonenkov.yandexdisksync.ui.theme.YandexRed
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(
    preferences: AppPreferences,
    syncEngine: SyncEngine,
    onNavigateToFolders: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val folders by preferences.foldersFlow.collectAsState()
    val progress by syncEngine.progressFlow.collectAsState()

    var diskInfo by remember { mutableStateOf<DiskInfo?>(null) }
    var diskInfoLoading by remember { mutableStateOf(false) }

    LaunchedEffect(preferences.oauthToken) {
        if (preferences.oauthToken.isNotBlank()) {
            diskInfoLoading = true
            try {
                val client = YandexDiskClient(preferences.oauthToken)
                diskInfo = client.getDiskInfo()
            } catch (e: Exception) {
                // Ignore silent load error
            } finally {
                diskInfoLoading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Карточка аккаунта / диска
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = if (preferences.oauthToken.isNotBlank()) SuccessGreen else Color.Gray
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (preferences.oauthToken.isNotBlank()) {
                                    diskInfo?.user?.displayName ?: diskInfo?.user?.login ?: "Яндекс Диск подключен"
                                } else {
                                    "Токен не настроен"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (preferences.oauthToken.isBlank()) {
                            TextButton(onClick = onNavigateToSettings) {
                                Text("Настроить")
                            }
                        }
                    }

                    if (diskInfo != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val used = diskInfo!!.usedSpace
                        val total = diskInfo!!.totalSpace
                        val fraction = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = YandexRed
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Занято ${formatBytes(used)} из ${formatBytes(total)} (${(fraction * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Основной блок управления ручной синхронизацией
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (progress.isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (progress.isRunning) {
                        Text(
                            text = progress.actionText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (progress.currentFolderName.isNotBlank()) {
                            Text(
                                text = "Папка: ${progress.currentFolderName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (progress.currentFileName.isNotBlank()) {
                            Text(
                                text = "Файл: ${progress.currentFileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { progress.percent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { syncEngine.cancelSync() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Остановить")
                        }
                    } else {
                        Text(
                            text = "Ручной запуск синхронизации",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Синхронизирует все включенные папки прямо сейчас",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                SyncForegroundService.startSync(context)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = preferences.oauthToken.isNotBlank() && folders.any { it.enabled },
                            colors = ButtonDefaults.buttonColors(containerColor = YandexRed)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "СИНХРОНИЗИРОВАТЬ СЕЙЧАС",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Заголовок секции папок
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Папки для синхронизации (${folders.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onNavigateToFolders) {
                    Text("Настроить все")
                }
            }
        }

        if (folders.isEmpty()) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Нет добавленных папок",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Добавьте папки телефона (например Фотографии или Документы), чтобы синхронизировать их с Яндекс Диском",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onNavigateToFolders) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить папку")
                        }
                    }
                }
            }
        } else {
            items(folders) { folder ->
                FolderSummaryCard(
                    folder = folder,
                    onToggleEnabled = { enabled ->
                        preferences.addOrUpdateFolder(folder.copy(enabled = enabled))
                    },
                    onSyncSingle = {
                        scope.launch {
                            syncEngine.syncSingleFolder(folder)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FolderSummaryCard(
    folder: SyncFolderPair,
    onToggleEnabled: (Boolean) -> Unit,
    onSyncSingle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Облако: ${folder.remotePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Режим: ${folder.mode.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (folder.lastSyncTime > 0) {
                    val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(folder.lastSyncTime))
                    Text(
                        text = "Последняя синхронизация: $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            IconButton(onClick = onSyncSingle) {
                Icon(Icons.Default.Refresh, contentDescription = "Синхронизировать эту папку")
            }

            Switch(
                checked = folder.enabled,
                onCheckedChange = onToggleEnabled
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format("%.1f КБ", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
