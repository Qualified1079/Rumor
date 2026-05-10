package com.rumor.mesh.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.model.MessageType
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.ui.theme.AwayGrey
import com.rumor.mesh.ui.theme.OnlineGreen
import com.rumor.mesh.ui.theme.RecentlyAmber
import org.koin.androidx.compose.koinViewModel

@Composable
fun MessagesScreen(
    onOpenThread: (peerId: String) -> Unit,
    viewModel: MessagesViewModel = koinViewModel(),
) {
    val threads by viewModel.threads.collectAsState()

    if (threads.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No conversations yet — open a contact to start one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn {
        items(threads, key = { it.peerId }) { thread ->
            ThreadRow(thread, onClick = { onOpenThread(thread.peerId) })
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, onClick: () -> Unit) {
    val statusColor = when (thread.presence?.status) {
        OnlineStatus.ONLINE   -> OnlineGreen
        OnlineStatus.RECENTLY -> RecentlyAmber
        else                  -> AwayGrey
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Avatar(
                initial = (thread.contact?.displayName?.firstOrNull() ?: '#').uppercaseChar(),
                statusColor = statusColor,
            )
        },
        headlineContent = {
            Text(
                text = thread.contact?.displayName ?: thread.peerId.take(16).plus("…"),
                fontWeight = if (thread.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Text(
                text = previewText(thread.lastMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatElapsed(System.currentTimeMillis() - thread.lastMessage.sentAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                if (thread.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = thread.unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Avatar(initial: Char, statusColor: Color) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Surface(
            modifier = Modifier.size(44.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = initial.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
    }
}

private fun previewText(msg: com.rumor.mesh.core.model.RumorMessage): String = when {
    msg.encryptedPayload != null -> "[encrypted]"
    msg.type == MessageType.TRANSFER_METADATA -> "[transfer]"
    else -> msg.payload?.content ?: ""
}

private fun formatElapsed(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec < 60   -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        sec < 86_400 -> "${sec / 3600}h"
        else -> "${sec / 86_400}d"
    }
}

