package com.rumor.mesh.ui.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import org.koin.androidx.compose.koinViewModel

@Composable
fun InboxPolicyScreen(
    viewModel: InboxPolicyViewModel = koinViewModel(),
) {
    val policy by viewModel.policy.collectAsState()

    var capInput by remember(policy.maxIncomingBytes) {
        mutableStateOf(policy.maxIncomingBytes?.let { (it / 1_000_000L).toString() } ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Inbox policy", style = MaterialTheme.typography.titleLarge)
        Text(
            "These rules control what reaches your inbox. They never affect what your node forwards to the mesh — blocked or filtered traffic still relays normally.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = "Only accept media from contacts",
            subtitle = "Strangers can still send text. Images, voice, and files from unknown senders are silently dropped before reaching your inbox.",
            checked = policy.contactsOnlyMedia,
            onCheckedChange = viewModel::setContactsOnlyMedia,
        )

        SwitchRow(
            title = "Reject unknown transfers",
            subtitle = "Drop chunked file announcements from non-contacts before any chunks are received.",
            checked = policy.rejectUnknownTransfers,
            onCheckedChange = viewModel::setRejectUnknownTransfers,
        )

        Spacer(Modifier.height(8.dp))

        Text("Maximum incoming transfer size", style = MaterialTheme.typography.titleSmall)
        Text(
            "Files larger than this are rejected before reassembly starts. Leave blank for no limit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = capInput,
                onValueChange = { new ->
                    capInput = new.filter { it.isDigit() }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("No limit") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("MB") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val mb = capInput.toLongOrNull()?.takeIf { it > 0 }
                viewModel.setMaxIncomingMegabytes(mb)
            }) {
                Text("Apply")
            }
        }

        if (policy.maxIncomingBytes != null) {
            TextButton(onClick = {
                capInput = ""
                viewModel.setMaxIncomingMegabytes(null)
            }) {
                Text("Clear limit")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}
