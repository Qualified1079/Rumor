package com.rumor.mesh.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-launch onboarding: set a passphrase, generate identity.
 * Also used as the unlock screen on subsequent launches.
 */
@Composable
fun OnboardingScreen(
    isFirstLaunch: Boolean,
    onPassphraseConfirmed: (passphrase: String) -> Unit,
    errorMessage: String? = null,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    val isValid = if (isFirstLaunch) {
        passphrase.length >= 8 && passphrase == confirm
    } else {
        passphrase.isNotBlank()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = if (isFirstLaunch) "Create your identity" else "Unlock Rumor",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = if (isFirstLaunch) {
                "Choose a passphrase to protect your identity on this device. " +
                "If you forget it, your identity cannot be recovered."
            } else {
                "Enter your passphrase to unlock the mesh."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            visualTransformation = if (showPassphrase) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                    Icon(
                        if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassphrase) "Hide" else "Show",
                    )
                }
            },
            isError = errorMessage != null,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isFirstLaunch) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("Confirm passphrase") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = confirm.isNotEmpty() && passphrase != confirm,
                supportingText = {
                    if (confirm.isNotEmpty() && passphrase != confirm) {
                        Text("Passphrases don't match")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onPassphraseConfirmed(passphrase) },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isFirstLaunch) "Create identity" else "Unlock")
        }

        if (isFirstLaunch) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Min 8 characters. Your passphrase is never transmitted.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
