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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.simonenkov.yandexdisksync.data.AppPreferences
import ru.simonenkov.yandexdisksync.data.SyncFolderPair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    preferences: AppPreferences
) {
    val folders by preferences.foldersFlow.collectAsState()
    var editingFolder by remember { mutableStateOf<SyncFolderPair?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить папку")
            }
        }
    ) { paddingValues ->
        if (folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Список папок пуст",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Нажмите кнопку «+», чтобы выбрать локальную папку на телефоне и настроить её синхронизацию с Яндекс Диском.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(folders, key = { it.id }) { folder ->
                    FolderDetailCard(
                        folder = folder,
                        onToggleEnabled = { enabled ->
                            preferences.addOrUpdateFolder(folder.copy(enabled = enabled))
                        },
                        onEdit = {
                            editingFolder = folder
                            showAddDialog = true
                        },
                        onDelete = {
                            preferences.removeFolder(folder.id)
                        }
                    )
                }
            }
        }

        if (showAddDialog) {
            AddEditFolderDialog(
                initialFolder = editingFolder,
                onDismiss = {
                    showAddDialog = false
                    editingFolder = null
                },
                onSave = { updatedFolder ->
                    preferences.addOrUpdateFolder(updatedFolder)
                    showAddDialog = false
                    editingFolder = null
                }
            )
        }
    }
}

@Composable
fun FolderDetailCard(
    folder: SyncFolderPair,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = folder.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = folder.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Локально: ${folder.localReadablePath}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Яндекс Диск: ${folder.remotePath}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            AssistChip(
                onClick = {},
                label = { Text(folder.mode.title) },
                leadingIcon = {
                    Icon(
                        when (folder.mode) {
                            ru.simonenkov.yandexdisksync.data.SyncMode.TWO_WAY -> Icons.Default.SyncAlt
                            ru.simonenkov.yandexdisksync.data.SyncMode.UPLOAD_ONLY -> Icons.Default.CloudUpload
                            ru.simonenkov.yandexdisksync.data.SyncMode.DOWNLOAD_ONLY -> Icons.Default.CloudDownload
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            if (folder.compressPhotos && folder.mode == ru.simonenkov.yandexdisksync.data.SyncMode.UPLOAD_ONLY) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\u0424\u043e\u0442\u043e \u0441\u0436\u0438\u043c\u0430\u044e\u0442\u0441\u044f: JPEG ${folder.effectiveQuality}%, " + if (folder.maxImageSide > 0) "\u0434\u043e ${folder.maxImageSide} px" else "\u0438\u0441\u0445\u043e\u0434\u043d\u044b\u0439 \u0440\u0430\u0437\u043c\u0435\u0440",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (folder.lastSyncStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Статус: ${folder.lastSyncStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Изменить")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Удалить")
                }
            }
        }
    }
}

