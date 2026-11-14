package ru.simonenkov.yandexdisksync.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.simonenkov.yandexdisksync.data.SyncFolderPair
import ru.simonenkov.yandexdisksync.data.SyncMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditFolderDialog(
    initialFolder: SyncFolderPair? = null,
    onDismiss: () -> Unit,
    onSave: (SyncFolderPair) -> Unit
) {
    val context = LocalContext.current

    var displayName by remember { mutableStateOf(initialFolder?.displayName.orEmpty()) }
    var selectedUri by remember { mutableStateOf(initialFolder?.localTreeUri.orEmpty()) }
    var readablePath by remember { mutableStateOf(initialFolder?.localReadablePath.orEmpty()) }
    var remotePath by remember { mutableStateOf(initialFolder?.remotePath ?: "/PhoneSync/") }
    var selectedMode by remember { mutableStateOf(initialFolder?.mode ?: SyncMode.TWO_WAY) }
    var compressPhotos by remember { mutableStateOf(initialFolder?.compressPhotos ?: false) }
    var jpegQuality by remember { mutableStateOf(initialFolder?.effectiveQuality ?: 82) }
    var maxImageSide by remember { mutableStateOf(initialFolder?.maxImageSide ?: 2560) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Ignore if already granted
            }

            selectedUri = uri.toString()
            val decoded = Uri.decode(uri.lastPathSegment.orEmpty())
            readablePath = decoded.replace("primary:", "Основная память: /")
            if (displayName.isBlank()) {
                displayName = readablePath.substringAfterLast('/').ifBlank { "Папка" }
            }
            if (remotePath == "/PhoneSync/") {
                val folderName = readablePath.substringAfterLast('/').ifBlank { "Folder" }
                remotePath = "/PhoneSync/$folderName"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialFolder == null) "Добавить папку для синхронизации" else "Редактировать папку")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Название (для себя)") },
                    placeholder = { Text("Например: Фотографии, Документы") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openDocumentTreeLauncher.launch(null) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Локальная папка на телефоне",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (readablePath.isNotBlank()) readablePath else "Нажмите, чтобы выбрать папку",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = { Text("Папка на Яндекс Диске") },
                    placeholder = { Text("/PhoneSync/Photos") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = "Режим синхронизации:",
                    style = MaterialTheme.typography.labelMedium
                )

                SyncMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mode }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = mode.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedMode == SyncMode.UPLOAD_ONLY) {
                    Divider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("\u0421\u0436\u0438\u043c\u0430\u0442\u044c \u0444\u043e\u0442\u043e \u043f\u0440\u0438 \u043e\u0442\u043f\u0440\u0430\u0432\u043a\u0435", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "\u042d\u043a\u043e\u043d\u043e\u043c\u0438\u0442 \u043c\u0435\u0441\u0442\u043e \u0432 \u043e\u0431\u043b\u0430\u043a\u0435. \u041e\u0440\u0438\u0433\u0438\u043d\u0430\u043b\u044b \u043d\u0430 \u0442\u0435\u043b\u0435\u0444\u043e\u043d\u0435 \u043d\u0435 \u0438\u0437\u043c\u0435\u043d\u044f\u044e\u0442\u0441\u044f. \u0422\u043e\u043b\u044c\u043a\u043e \u0444\u043e\u0442\u043e JPEG/HEIC.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = compressPhotos, onCheckedChange = { compressPhotos = it })
                    }
                    if (compressPhotos) {
                        Text("\u041a\u0430\u0447\u0435\u0441\u0442\u0432\u043e JPEG: $jpegQuality%", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = jpegQuality.toFloat(),
                            onValueChange = { jpegQuality = it.toInt() },
                            valueRange = 50f..95f,
                            steps = 8
                        )
                        Text("\u0420\u0430\u0437\u043c\u0435\u0440 \u043f\u043e \u0434\u043b\u0438\u043d\u043d\u043e\u0439 \u0441\u0442\u043e\u0440\u043e\u043d\u0435", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "\u041e\u0440\u0438\u0433\u0438\u043d\u0430\u043b", 1920 to "1920", 2560 to "2560", 3840 to "3840").forEach { (side, label) ->
                                FilterChip(
                                    selected = maxImageSide == side,
                                    onClick = { maxImageSide = side },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedUri.isNotBlank()) {
                        val folder = (initialFolder ?: SyncFolderPair()).copy(
                            displayName = displayName.ifBlank { "Синхронизируемая папка" },
                            localTreeUri = selectedUri,
                            localReadablePath = readablePath,
                            remotePath = if (remotePath.startsWith("/")) remotePath else "/$remotePath",
                            mode = selectedMode,
                            compressPhotos = compressPhotos,
                            jpegQuality = jpegQuality,
                            maxImageSide = maxImageSide
                        )
                        onSave(folder)
                    }
                },
                enabled = selectedUri.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

