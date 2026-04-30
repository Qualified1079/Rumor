package com.rumor.mesh.ui.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rumor.mesh.core.routing.OnlineStatus
import com.rumor.mesh.ui.theme.AwayGrey
import com.rumor.mesh.ui.theme.OnlineGreen
import com.rumor.mesh.ui.theme.RecentlyAmber

@Composable
fun ContactsScreen(
    onOpenThread: (String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
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
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cws.contact.displayName ?: cws.contact.userId.take(16) + "…",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (cws.contact.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = "Verified",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = cws.contact.userId.take(24) + "…",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        },
        leadingContent = {
            Box(contentAlignment = Alignment.BottomEnd) {
                // Avatar circle with initials
                Surface(
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = (cws.contact.displayName?.firstOrNull() ?: '#').toString().uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                // Online status dot
                val dotColor = when (cws.presence?.status) {
                    OnlineStatus.ONLINE   -> OnlineGreen
                    OnlineStatus.RECENTLY -> RecentlyAmber
                    else                  -> AwayGrey
                }
                Surface(
                    modifier = Modifier.size(12.dp).clip(CircleShape),
                    color = dotColor,
                ) {}
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                val statusLabel = when (cws.presence?.status) {
                    OnlineStatus.ONLINE   -> "Online"
                    OnlineStatus.RECENTLY -> "Recently"
                    OnlineStatus.AWAY, null -> ""
                }
                if (statusLabel.isNotEmpty()) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (cws.presence?.status) {
                            OnlineStatus.ONLINE   -> OnlineGreen
                            OnlineStatus.RECENTLY -> RecentlyAmber
                            else                  -> AwayGrey
                        },
                    )
                }
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.MoreVert,
                        contentDescription = "Options",
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Message") },
                        onClick = { expanded = false; onOpenThread() },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Auto-relay")
                                Switch(
                                    checked = cws.contact.autoRelay,
                                    onCheckedChange = { onSetAutoRelay(it) },
                                    modifier = Modifier.scale(0.8f),
                                )
                            }
                        },
                        onClick = { onSetAutoRelay(!cws.contact.autoRelay) },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Always save")
                                Switch(
                                    checked = cws.contact.alwaysSave,
                                    onCheckedChange = { onSetAlwaysSave(it) },
                                    modifier = Modifier.scale(0.8f),
                                )
                            }
                        },
                        onClick = { onSetAlwaysSave(!cws.contact.alwaysSave) },
                    )
                }
            }
        },
    )
}

private fun androidx.compose.ui.Modifier.scale(factor: Float) =
    this.then(androidx.compose.ui.layout.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(
            (placeable.width * factor).toInt(),
            (placeable.height * factor).toInt()
        ) {
            placeable.placeRelative(
                ((placeable.width * factor - placeable.width) / 2).toInt(),
                ((placeable.height * factor - placeable.height) / 2).toInt()
            )
        }
    })
