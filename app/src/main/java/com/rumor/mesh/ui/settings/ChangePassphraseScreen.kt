package com.rumor.mesh.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import com.rumor.mesh.ui.SecureScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePassphraseScreen(
    onBack: () -> Unit = {},
    viewModel: ChangePassphraseViewModel = koinViewModel(),
) {
    SecureScreen()  // O165: keep secrets out of screenshots + the Recents card

    val state by viewModel.state.collectAsState()

    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    LaunchedEffect(state.success) {
        if (state.success) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change passphrase") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your passphrase encrypts your private key on this device. Changing it re-encrypts the key — it does not create a new identity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )

            OutlinedTextField(
                value = current,
                onValueChange = { current = it; viewModel.clearError() },
                label = { Text("Current passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = new,
                onValueChange = { new = it; viewModel.clearError() },
                label = { Text("New passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; viewModel.clearError() },
                label = { Text("Confirm new passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = confirm.isNotEmpty() && new != confirm,
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { viewModel.changePassphrase(current, new, confirm) },
                enabled = !state.busy && current.isNotBlank() && new.length >= 8 && new == confirm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(if (state.busy) "Changing…" else "Change passphrase")
            }

            Text(
                "Min 8 characters. Your passphrase is never transmitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}
