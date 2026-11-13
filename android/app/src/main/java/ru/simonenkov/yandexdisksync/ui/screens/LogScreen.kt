package ru.simonenkov.yandexdisksync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.simonenkov.yandexdisksync.engine.LogLevel
import ru.simonenkov.yandexdisksync.engine.SyncEngine
import ru.simonenkov.yandexdisksync.engine.SyncLogItem
import ru.simonenkov.yandexdisksync.ui.theme.ErrorRed
import ru.simonenkov.yandexdisksync.ui.theme.SuccessGreen
import ru.simonenkov.yandexdisksync.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    syncEngine: SyncEngine
) {
    val logs by syncEngine.logsFlow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал операций") },
                actions = {
                    IconButton(onClick = { syncEngine.clearLogs() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить лог")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Журнал пуст. Запустите синхронизацию для просмотра логов.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color(0xFF1E1E1E)),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { item ->
                    LogItemRow(item)
                }
            }
        }
    }
}

@Composable
fun LogItemRow(item: SyncLogItem) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
    val color = when (item.level) {
        LogLevel.INFO -> Color(0xFFE0E0E0)
        LogLevel.SUCCESS -> SuccessGreen
        LogLevel.WARNING -> WarningOrange
        LogLevel.ERROR -> ErrorRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "[$timeStr] ",
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = item.message,
            color = color,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
