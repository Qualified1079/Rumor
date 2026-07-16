package com.rumor.mesh.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rumor.mesh.core.protocol.MetricsSnapshot
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugMetricsScreen(
    onBack: () -> Unit = {},
    viewModel: DebugMetricsViewModel = koinViewModel(),
) {
    val snap by viewModel.metrics.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Node metrics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetricsSection("Dedup") {
                MetricRow("Total received", snap.dedupTotal.toString())
                MetricRow("Duplicates suppressed", snap.dedupHits.toString())
                MetricRow("Hit rate", "%.1f%%".format(snap.dedupHitRate * 100))
            }
            MetricsSection("Security") {
                MetricRow("Signature failures", snap.sigFailures.toString())
            }
            MetricsSection("Exchanges") {
                MetricRow("Successes", snap.exchangeSuccesses.toString())
                MetricRow("Failures", snap.exchangeFailures.toString())
                val total = snap.exchangeSuccesses + snap.exchangeFailures
                val rate = if (total > 0) snap.exchangeSuccesses * 100f / total else 0f
                MetricRow("Success rate", "%.1f%%".format(rate))
                MetricRow("Avg RTT", "${snap.avgRttMs} ms")
            }
            MetricsSection("Relay") {
                MetricRow("Messages relayed", snap.relayedMessages.toString())
                MetricRow("Outbound queue depth", snap.queueDepth.toString())
            }
        }
    }
}

@Composable
private fun ColumnScope.MetricsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(Modifier.padding(bottom = 4.dp))
    Column { content() }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
