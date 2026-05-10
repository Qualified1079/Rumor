package com.rumor.mesh.ui.transfers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.model.ContentType
import com.rumor.mesh.core.model.TransferDirection
import com.rumor.mesh.core.model.TransferStatus
import com.rumor.mesh.data.TransferEntity
import org.koin.androidx.compose.koinViewModel

/**
 * Standalone screen showing every recent transfer. Cards are individually
 * reusable — the same composable is used inline by Feed/Thread (future).
 */
@Composable
fun TransfersScreen(viewModel: TransfersViewModel = koinViewModel()) {
    val rows by viewModel.rows.collectAsState()

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No transfers yet.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.transfer.transferId }) { row ->
            TransferCard(row)
        }
    }
}

@Composable
fun TransferCard(row: TransferRow) {
    val t = row.transfer
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(t.contentType), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = t.title ?: t.transferId.take(12) + "…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${directionLabel(t.direction)} • ${formatBytes(t.totalBytes)} • ${t.totalChunks} chunks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                StatusBadge(t.status, isReceiving = t.direction == TransferDirection.RECEIVING)
            }

            Spacer(Modifier.height(8.dp))

            when (t.status) {
                TransferStatus.IN_PROGRESS -> {
                    LinearProgressIndicator(
                        progress = { row.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    val pctText = "${(row.fraction * 100).toInt()}% (${row.receivedChunks}/${t.totalChunks})"
                    Text(
                        text = if (t.direction == TransferDirection.RECEIVING)
                            "$pctText — waiting for chunks; NACKs sent on backoff"
                        else
                            "$pctText — sending",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                TransferStatus.COMPLETE -> {
                    LinearProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Complete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TransferStatus.ABANDONED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Abandoned after retry limit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TransferStatus.FAILED -> {
                    Text(
                        text = "Failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TransferStatus, isReceiving: Boolean) {
    val (label, color) = when (status) {
        TransferStatus.IN_PROGRESS -> (if (isReceiving) "Receiving" else "Sending") to MaterialTheme.colorScheme.primary
        TransferStatus.COMPLETE    -> "Done"      to MaterialTheme.colorScheme.primary
        TransferStatus.ABANDONED   -> "Abandoned" to MaterialTheme.colorScheme.error
        TransferStatus.FAILED      -> "Failed"    to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = color,
            disabledContainerColor = Color.Transparent,
        ),
    )
}

private fun iconFor(ct: ContentType): ImageVector = when (ct) {
    ContentType.IMAGE -> Icons.Default.Image
    ContentType.VOICE -> Icons.Default.AudioFile
    ContentType.FILE  -> Icons.Default.UploadFile
    else              -> Icons.Default.Description
}

private fun directionLabel(direction: TransferDirection): String = when (direction) {
    TransferDirection.SENDING   -> "↑ outgoing"
    TransferDirection.RECEIVING -> "↓ incoming"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_000           -> "$bytes B"
    bytes < 1_000_000       -> "${bytes / 1_000} KB"
    bytes < 1_000_000_000   -> String.format("%.1f MB", bytes / 1_000_000.0)
    else                    -> String.format("%.2f GB", bytes / 1_000_000_000.0)
}
