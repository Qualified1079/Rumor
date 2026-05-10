package com.rumor.mesh.ui.logs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.logging.LogEntry
import com.rumor.mesh.core.logging.LogLevel
import com.rumor.mesh.core.logging.RumorLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only viewer over [RumorLog]'s in-memory ring buffer. The buffer is a
 * pure global object, so this screen does not need a ViewModel.
 */
@Composable
fun LogsScreen() {
    val entries by RumorLog.entries.collectAsState()
    val listState = rememberLazyListState()

    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    val visible = remember(entries, minLevel) { entries.filter { it.level >= minLevel } }

    // Auto-scroll to newest entry when buffer grows.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { RumorLog.clear() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear log buffer")
            }
        }

        LevelFilterRow(selected = minLevel, onSelect = { minLevel = it })

        HorizontalDivider()

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No log entries yet.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(visible) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LevelFilterRow(selected: LogLevel, onSelect: (LogLevel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (level in LogLevel.values()) {
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level.name.take(1)) },
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR   -> MaterialTheme.colorScheme.error
        LogLevel.WARN    -> Color(0xFFE0A030)
        LogLevel.INFO    -> MaterialTheme.colorScheme.onSurface
        LogLevel.DEBUG   -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "${formatTime(entry.timestampMs)} ${entry.level.name.first()}/${entry.tag}",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
        entry.throwable?.let { t ->
            Text(
                text = t.stackTraceToString().lineSequence().take(6).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))
