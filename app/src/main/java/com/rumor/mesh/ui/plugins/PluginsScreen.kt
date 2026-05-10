package com.rumor.mesh.ui.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@Composable
fun PluginsScreen(
    viewModel: PluginsViewModel = koinViewModel(),
) {
    val rows by viewModel.rows.collectAsState()

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No plugins available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        return
    }

    val grouped = rows.groupBy { it.descriptor.category }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Plugins", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Toggle bridges and other plugins on or off. Disabled plugins stop receiving messages immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        for ((category, categoryRows) in grouped) {
            item(key = "header-$category") {
                Text(
                    text = category.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(categoryRows, key = { it.descriptor.pluginId }) { row ->
                PluginRow(
                    row = row,
                    onToggle = { viewModel.setEnabled(row.descriptor.pluginId, it) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PluginRow(
    row: PluginRow,
    onToggle: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.descriptor.displayName) },
        supportingContent = {
            Text(
                row.descriptor.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        },
        trailingContent = {
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
            )
        },
    )
}
