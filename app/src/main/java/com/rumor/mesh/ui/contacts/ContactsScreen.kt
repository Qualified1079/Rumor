package com.rumor.mesh.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rumor.mesh.core.model.OnlineStatus
import com.rumor.mesh.ui.theme.AwayGrey
import com.rumor.mesh.ui.theme.OnlineGreen
import com.rumor.mesh.ui.theme.RecentlyAmber

@Composable
fun ContactsScreen(
    onOpenThread: (peerId: String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsState()

    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No contacts yet — connect to peers to discover them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(contacts, key = { it.contact.userId }) { cws ->
            ContactRow(
                cws = cws,
                onOpenThread = { onOpenThread(cws.contact.userId) },
                onSetAutoRelay = { viewModel.setAutoRelay(cws.contact.userId, it) },
                onSetAlwaysSave = { viewModel.setAlwaysSave(cws.contact.userId, it) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun ContactRow(
    cws: ContactWithStatus,
    onOpenThread: () -> Unit,
    onSetAutoRelay: (Boolean) -> Unit,
    onSetAlwaysSave: (Boolean) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val statusColor = presenceColor(cws.presence?.status)

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cws.contact.displayName ?: truncatedId(cws.contact.userId),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (cws.contact.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = "Verified key",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = truncatedId(cws.contact.userId),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        },
        leadingContent = {
            ContactAvatar(
                initial = (cws.contact.displayName?.firstOrNull() ?: '#').uppercaseChar(),
                statusColor = statusColor,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusLabel(status = cws.presence?.status)
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                    }
                    ContactMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        autoRelay = cws.contact.autoRelay,
                        alwaysSave = cws.contact.alwaysSave,
                        onMessage = { menuExpanded = false; onOpenThread() },
                        onSetAutoRelay = onSetAutoRelay,
                        onSetAlwaysSave = onSetAlwaysSave,
                    )
                }
            }
        },
    )
}

@Composable
private fun ContactAvatar(initial: Char, statusColor: Color) {
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

@Composable
private fun StatusLabel(status: OnlineStatus?) {
    val (label, color) = when (status) {
        OnlineStatus.ONLINE   -> "Online"   to OnlineGreen
        OnlineStatus.RECENTLY -> "Recently" to RecentlyAmber
        else                  -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun ContactMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    autoRelay: Boolean,
    alwaysSave: Boolean,
    onMessage: () -> Unit,
    onSetAutoRelay: (Boolean) -> Unit,
    onSetAlwaysSave: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Message") },
            onClick = onMessage,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-relay")
                    Switch(checked = autoRelay, onCheckedChange = null, modifier = Modifier.height(24.dp))
                }
            },
            onClick = { onSetAutoRelay(!autoRelay) },
        )
        DropdownMenuItem(
            text = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Always save")
                    Switch(checked = alwaysSave, onCheckedChange = null, modifier = Modifier.height(24.dp))
                }
            },
            onClick = { onSetAlwaysSave(!alwaysSave) },
        )
    }
}

private fun presenceColor(status: OnlineStatus?): Color = when (status) {
    OnlineStatus.ONLINE   -> OnlineGreen
    OnlineStatus.RECENTLY -> RecentlyAmber
    else                  -> AwayGrey
}

private fun truncatedId(userId: String): String =
    if (userId.length > 20) userId.take(16) + "…" else userId
