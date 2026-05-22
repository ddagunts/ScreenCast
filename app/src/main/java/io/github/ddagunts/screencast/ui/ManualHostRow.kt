package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

// Free-text IP / hostname entry. No validation — the user can type anything;
// failure happens at connect time the same way it does for an unreachable
// mDNS device. The "Add" button is disabled only on whitespace-only input so
// the persistence layer doesn't fill up with empty strings.
@Composable
fun ManualHostRow(
    label: String,
    onAdd: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    fun submit() {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        onAdd(trimmed)
        input = ""
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(0.dp))
        Button(
            onClick = { submit() },
            enabled = input.trim().isNotEmpty(),
        ) {
            Text("Add")
        }
    }
}
