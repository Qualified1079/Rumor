package com.rumor.mesh.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.model.UserMode
import com.rumor.mesh.core.policy.ModeStateManager
import com.rumor.mesh.ui.formatElapsed
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(
    onOpenPlugins: () -> Unit = {},
    onOpenInboxPolicy: () -> Unit = {},
    onOpenBlocks: () -> Unit = {},
    onOpenTransfers: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenMetrics: () -> Unit = {},
    onOpenChangePassphrase: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(8.dp))

        // ── Node identity ─────────────────────────────────────────────────
        SectionHeader("Identity")
        InfoRow(label = "User ID", value = state.userId?.take(32)?.plus("…") ?: "Locked")
        InfoRow(label = "Device ID", value = state.deviceId?.take(16)?.plus("…") ?: "—")

        SettingRow(
            icon = Icons.Default.Key,
            title = "Change passphrase",
            onClick = onOpenChangePassphrase,
        )

        Spacer(Modifier.height(4.dp))

        // ── Privacy & filters ─────────────────────────────────────────────
        SectionHeader("Privacy & filters")
        SettingRow(
            icon = Icons.Default.Block,
            title = "Blocked users & subscriptions",
            onClick = onOpenBlocks,
        )
        SettingRow(
            icon = Icons.Default.Inbox,
            title = "Inbox policy",
            onClick = onOpenInboxPolicy,
        )

        Spacer(Modifier.height(4.dp))

        // ── Plugins ───────────────────────────────────────────────────────
        SectionHeader("Plugins")
        SettingRow(
            icon = Icons.Default.Extension,
            title = "Manage plugins",
            onClick = onOpenPlugins,
        )

        Spacer(Modifier.height(4.dp))

        // ── Transfers ─────────────────────────────────────────────────────
        SectionHeader("Transfers")
        SettingRow(
            icon = Icons.Default.Storage,
            title = "Transfer history",
            onClick = onOpenTransfers,
        )

        Spacer(Modifier.height(4.dp))

        // ── Duty cycle ────────────────────────────────────────────────────
        SectionHeader("Radio duty cycle")
        Text(
            "Scan interval: ${state.scanIntervalSec}s on / ${state.sleepIntervalSec}s sleep",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Slider(
            value = state.scanIntervalSec.toFloat(),
            onValueChange = { viewModel.setScanInterval(it.toInt()) },
            valueRange = 3f..30f,
            // One step per integer second so every whole-second value (including
            // the 5s default) is a valid snap position — steps=8 previously only
            // allowed multiples of 3, silently rounding 5 to 6 on any drag.
            steps = 26,
        )

        Spacer(Modifier.height(4.dp))

        // ── Node mode (O57/O80) ───────────────────────────────────────────
        SectionHeader("Node mode")
        ModeOption(
            title = "Auto",
            subtitle = "Static when plugged in, Free when plugged in with the screen " +
                "off, Mobile otherwise. Never slows down active use — Free only " +
                "engages while the screen is off." +
                if (state.modeAuto) " Currently: ${state.currentMode.name.lowercase()}." else "",
            selected = state.modeAuto,
            onClick = viewModel::setModeAuto,
        )
        ModeOption(
            title = "Mobile",
            subtitle = "Carried device. Conservative scanning — lightest on battery.",
            selected = !state.modeAuto && state.currentMode == UserMode.MOBILE,
            onClick = { viewModel.setModeManual(UserMode.MOBILE) },
        )
        ModeOption(
            title = "Static",
            subtitle = "Plugged-in, always-on device. Scans more often and caches " +
                "more for peers. Uses somewhat more battery; no noticeable slowdown.",
            selected = !state.modeAuto && state.currentMode == UserMode.STATIC,
            onClick = { viewModel.setModeManual(UserMode.STATIC) },
        )
        ModeOption(
            title = "Free",
            subtitle = "Scans continuously and syncs every few seconds. Drains battery " +
                "quickly and can cause Wi-Fi stutter while you use the phone — meant " +
                "for a device left on a charger.",
            selected = !state.modeAuto && state.currentMode == UserMode.FREE,
            onClick = { viewModel.setModeManual(UserMode.FREE) },
        )
        if (state.modeTransitions.isNotEmpty()) {
            Text(
                "Recent transitions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
            val now = System.currentTimeMillis()
            state.modeTransitions.take(5).forEach { t ->
                Text(
                    "${formatElapsed(now - t.atMs)} ago — ${t.mode.name.lowercase()} " +
                        if (t.source == ModeStateManager.Source.AUTO) "(auto)" else "(manual)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ── Debug ─────────────────────────────────────────────────────────
        SectionHeader("Debug")
        SwitchRow(
            icon = Icons.Default.BugReport,
            title = "Debug logging",
            checked = state.debugLogging,
            onCheckedChange = viewModel::setDebugLogging,
        )
        if (state.debugLogging) {
            TextButton(onClick = onOpenLogs) {
                Text("View logs")
            }
            SettingRow(
                icon = Icons.Default.Speed,
                title = "Node metrics",
                onClick = onOpenMetrics,
            )
        }

        // Battery optimisation warning
        if (state.showBatteryOptimisationWarning) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Power, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Battery optimisation may kill the mesh", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = viewModel::onOpenBatterySettings) {
                            Text("Fix this")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    HorizontalDivider()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
