package com.rumor.mesh.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.core.model.RumorMessage
import com.rumor.mesh.ui.theme.AwayGrey
import com.rumor.mesh.ui.theme.OnlineGreen
import com.rumor.mesh.ui.theme.RecentlyAmber
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    peerId: String,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = koinViewModel(),
) {
    LaunchedEffect(peerId) {
        viewModel.bind(peerId)
    }

    val messages by viewModel.messages.collectAsState()
    val header by viewModel.header.collectAsState()
    val listState = rememberLazyListState()
    var composeText by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            viewModel.markAllRead()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = header?.contact?.displayName ?: peerId.take(16) + "…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    StatusLabel(header?.presence?.status)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No messages yet — say hi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.raw.id }) { msg ->
                    MessageBubble(message = msg)
                }
            }
        }

        HorizontalDivider()
        ComposeBar(
            text = composeText,
            onTextChange = { composeText = it },
            onSend = {
                viewModel.send(composeText)
                composeText = ""
            },
        )
    }
}

@Composable
private fun StatusLabel(status: OnlineStatus?) {
    val (label, color) = when (status) {
        OnlineStatus.ONLINE   -> "Online"   to OnlineGreen
        OnlineStatus.RECENTLY -> "Recently" to RecentlyAmber
        OnlineStatus.AWAY     -> "Away"     to AwayGrey
        null                  -> return
    }
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun MessageBubble(message: DisplayMessage) {
    val isFromMe = message.isFromMe
    val bubbleColor = if (isFromMe)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isFromMe)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (isFromMe) 12.dp else 2.dp,
                bottomEnd = if (isFromMe) 2.dp else 12.dp,
            ),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.body,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatElapsed(System.currentTimeMillis() - message.raw.sentAtMs),
                    color = textColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun ComposeBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message…") },
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(Icons.Default.Send, contentDescription = "Send")
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec < 60   -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86_400 -> "${sec / 3600}h ago"
        else -> "${sec / 86_400}d ago"
    }
}
