package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// Simple modal that captures the 6-digit hex code shown on the TV after
// a successful CONFIGURATION_ACK exchange. The text field is hex-aware:
// uppercase-default and constrained to [0-9A-F] characters, but we let
// the user type more than 6 chars in case a future firmware changes the
// length — the pairing protocol's symbol_length field is what governs it.
@Composable
fun AndroidTvPairingDialog(
    deviceName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val trimmed = code.trim().uppercase()
    val canSubmit = trimmed.length >= 4 && trimmed.all { it in '0'..'9' || it in 'A'..'F' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with $deviceName") },
        text = {
            Column {
                Text(
                    "Enter the code shown on the TV.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(8) },
                    singleLine = true,
                    label = { Text("Pairing code") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canSubmit) onSubmit(trimmed) }, enabled = canSubmit) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
