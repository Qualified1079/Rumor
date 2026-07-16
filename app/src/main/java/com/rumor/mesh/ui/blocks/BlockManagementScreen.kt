package com.rumor.mesh.ui.blocks

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.model.BlocklistMode
import com.rumor.mesh.core.model.BlockEntry
import com.rumor.mesh.core.model.SubscribedBlocklist
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BlockManagementScreen(
    viewModel: BlockManagementViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Blocked", style = MaterialTheme.typography.titleLarge)
        Text(
            "Blocking suppresses what you see — your node still relays everything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAddDialog = true }) { Text("Block user") }
            OutlinedButton(onClick = { showExportDialog = true }) { Text("Export") }
            OutlinedButton(onClick = { showImportDialog = true }) { Text("Import") }
        }

        state.statusMessage?.let { msg ->
            Spacer(Modifier.height(4.dp))
            AssistChip(
                onClick = viewModel::clearStatus,
                label = { Text(msg) },
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Local blocks (${state.localBlocks.size})")

        if (state.localBlocks.isEmpty()) {
            Text(
                "No local blocks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        } else {
            for (entry in state.localBlocks) {
                LocalBlockRow(entry, onUnblock = { viewModel.unblock(entry.userId) })
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Subscribed blocklists (${state.subscriptions.size})")

        if (state.subscriptions.isEmpty()) {
            Text(
                "Not subscribed to any external blocklists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        } else {
            for (sub in state.subscriptions) {
                SubscriptionRow(sub, onUnsubscribe = { viewModel.unsubscribe(sub.publisherId) })
            }
        }
    }

    if (showAddDialog) {
        AddBlockDialog(
            onConfirm = { uid, durationMin, reason ->
                viewModel.block(uid, durationMin, reason)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showExportDialog) {
        ExportDialog(
            onExport = { passphrase ->
                viewModel.exportEncrypted(passphrase) { blob ->
                    showExportDialog = false
                    if (blob != null) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, blob)
                            putExtra(Intent.EXTRA_SUBJECT, "Rumor blocklist backup")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share blocklist"))
                    }
                }
            },
            onDismiss = { showExportDialog = false },
        )
    }

    if (showImportDialog) {
        ImportDialog(
            onImport = { blob, passphrase ->
                viewModel.importEncrypted(blob, passphrase)
                showImportDialog = false
            },
            onDismiss = { showImportDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@Composable
private fun LocalBlockRow(entry: BlockEntry, onUnblock: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.userId.take(24) + if (entry.userId.length > 24) "…" else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.expiresAtMs?.let {
                        "Expires ${formatTime(it)}"
                    } ?: "Permanent",
                    style = MaterialTheme.typography.labelSmall,
                )
                entry.reason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Default.Block, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onUnblock) {
                Icon(Icons.Default.Delete, contentDescription = "Unblock")
            }
        },
    )
}

@Composable
private fun SubscriptionRow(sub: SubscribedBlocklist, onUnsubscribe: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = sub.publisherId.take(24) + if (sub.publisherId.length > 24) "…" else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = "v${sub.currentVersion} • ${sub.mode.displayLabel()} • applied ${formatTime(sub.lastAppliedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        leadingContent = { Icon(Icons.Default.RssFeed, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onUnsubscribe) {
                Icon(Icons.Default.Delete, contentDescription = "Unsubscribe")
            }
        },
    )
}

private enum class DurationUnit(val label: String, val minutesPerUnit: Long) {
    MINUTES("Minutes", 1L),
    HOURS("Hours", 60L),
    DAYS("Days", 24L * 60L),
    WEEKS("Weeks", 7L * 24L * 60L),
    MONTHS("Months", 30L * 24L * 60L),
    YEARS("Years", 365L * 24L * 60L),
}

@Composable
private fun AddBlockDialog(
    onConfirm: (userId: String, durationMin: Long?, reason: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var userId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(DurationUnit.HOURS) }
    var permanent by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    val durationMin: Long? = if (permanent) null else amount.toLongOrNull()?.takeIf { it > 0 }?.times(unit.minutesPerUnit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text("User ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Duration", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() } },
                        enabled = !permanent,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    DurationUnitDropdown(
                        selected = unit,
                        enabled = !permanent,
                        onSelected = { unit = it },
                        modifier = Modifier.weight(1.5f),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = permanent, onCheckedChange = { permanent = it })
                    Text("Permanent")
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = userId.isNotBlank() && (permanent || durationMin != null),
                onClick = { onConfirm(userId, durationMin, reason.ifBlank { null }) },
            ) { Text("Block") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationUnitDropdown(
    selected: DurationUnit,
    enabled: Boolean,
    onSelected: (DurationUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DurationUnit.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ExportDialog(
    onExport: (passphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export encrypted blocklist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a passphrase. You'll need the same passphrase to import the file later.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = passphrase.length >= 8, onClick = { onExport(passphrase) }) {
                Text("Export")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(
    onImport: (blob: String, passphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var blob by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import encrypted blocklist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = blob,
                    onValueChange = { blob = it },
                    label = { Text("Encrypted blob") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = blob.isNotBlank() && passphrase.isNotBlank(),
                onClick = { onImport(blob, passphrase) },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

private fun BlocklistMode.displayLabel(): String = when (this) {
    BlocklistMode.ONE_TIME   -> "One-time"
    BlocklistMode.CONTINUOUS -> "Continuous"
}
