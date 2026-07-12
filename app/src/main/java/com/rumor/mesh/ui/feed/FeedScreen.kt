package com.rumor.mesh.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import com.rumor.mesh.core.model.RumorMessage
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FeedScreen(
    viewModel: FeedViewModel = koinViewModel(),
) {
    val broadcasts by viewModel.broadcasts.collectAsState()
    var composeText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(broadcasts.size) {
        if (broadcasts.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(broadcasts, key = { it.id }) { msg ->
                BroadcastCard(
                    message = msg,
                    isOwnMessage = viewModel.isOwnMessage(msg.senderId),
                    onRelay = { viewModel.relay(msg) },
                )
            }
        }

        HorizontalDivider()
        ComposeBar(
            text = composeText,
            onTextChange = { composeText = it },
            onSend = {
                viewModel.sendBroadcast(composeText)
                composeText = ""
            },
        )
    }
}

@Composable
private fun BroadcastCard(
    message: RumorMessage,
    isOwnMessage: Boolean,
    onRelay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.senderId.take(12) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatElapsed(System.currentTimeMillis() - message.sentAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Hops ${message.hopsToLive}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = message.payload?.content ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))

            if (!isOwnMessage) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (!message.wasRelayed) {
                        TextButton(
                            onClick = onRelay,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Repeat,
                                contentDescription = "Relay",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Relay", fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            "Relayed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Broadcast to mesh…") },
            maxLines = 4,
            singleLine = false,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

private fun formatElapsed(elapsedMs: Long): String {
    val sec = elapsedMs / 1000
    return when {
        sec < 60   -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        else       -> "${sec / 3600}h ago"
    }
}
