package ru.simonenkov.yandexdisksync.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.simonenkov.yandexdisksync.api.YandexDiskClient
import ru.simonenkov.yandexdisksync.data.AppPreferences
import ru.simonenkov.yandexdisksync.ui.theme.ErrorRed
import ru.simonenkov.yandexdisksync.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferences: AppPreferences
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(preferences.oauthToken) }
    var syncDeletions by remember { mutableStateOf(preferences.syncDeletions) }
    var wifiOnly by remember { mutableStateOf(preferences.wifiOnly) }
    var conflictResolution by remember { mutableStateOf(preferences.conflictResolution) }

    var checkStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Настройки аккаунта Яндекс",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                preferences.oauthToken = it
            },
            label = { Text("OAuth-токен доступа") },
            placeholder = { Text("y0_AgAAAA...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    isChecking = true
                    checkStatus = null
                    scope.launch {
                        try {
                            val client = YandexDiskClient(token)
                            val info = client.getDiskInfo()
                            val name = info.user?.displayName ?: info.user?.login ?: "OK"
                            checkStatus = "Успешно! Пользователь: $name"
                            isSuccess = true
                        } catch (e: Exception) {
                            checkStatus = "Ошибка: ${e.localizedMessage ?: e.message}"
                            isSuccess = false
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = token.isNotBlank() && !isChecking
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Проверить токен")
            }

            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://yandex.ru/dev/disk/poligon/")
                    )
                    context.startActivity(intent)
                }
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Получить токен")
            }
        }

        if (checkStatus != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isSuccess) SuccessGreen else ErrorRed
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = checkStatus!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSuccess) SuccessGreen else ErrorRed
                )
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "Параметры синхронизации",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Синхронизировать удаление файлов", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Если файл удален в одной стороне, удалять его и во второй",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = syncDeletions,
                onCheckedChange = {
                    syncDeletions = it
                    preferences.syncDeletions = it
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Синхронизация только по Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Экономит мобильный трафик при выгрузке файлов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    preferences.wifiOnly = it
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Версия приложения: 1.2.2 Android Edition",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
