package ru.simonenkov.yandexdisksync.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.simonenkov.yandexdisksync.updater.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text(
                text = "Доступно обновление ${updateInfo.versionName}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (updateInfo.releaseTitle.isNotBlank()) {
                    Text(
                        text = updateInfo.releaseTitle,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                if (updateInfo.changelog.isNotBlank()) {
                    Text(
                        text = updateInfo.changelog,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (updateInfo.sizeBytes > 0) {
                    val sizeMb = updateInfo.sizeBytes / (1024.0 * 1024.0)
                    Text(
                        text = "Размер: ${String.format("%.1f", sizeMb)} МБ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Загрузка APK: ${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onStartDownload,
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "Скачивание..." else "Скачать и установить")
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Позже")
                }
            }
        }
    )
}
