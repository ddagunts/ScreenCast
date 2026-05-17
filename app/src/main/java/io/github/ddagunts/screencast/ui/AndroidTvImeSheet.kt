package io.github.ddagunts.screencast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ddagunts.screencast.androidtv.AndroidTvImePrompt
import kotlinx.coroutines.delay

// Live-typing bottom sheet for sending text to a TV text field.
//
// Behaviour (matches the user's chosen design):
//   * Auto-shown by the caller whenever the session's imePrompt is non-null.
//     A manual "keyboard" button on the remote screen synthesises an empty
//     prompt to force-open this sheet against an unfocused field.
//   * Pre-populated from the TV's last-known field state, cursor at the
//     TV's reported selection. The phone session does the diffing — this
//     composable just emits the raw new text on every keystroke.
//   * Per-keystroke send with a 50 ms debounce. We use LaunchedEffect keyed
//     on the local text: every keystroke cancels the previous effect and
//     starts a fresh delay, so a burst of fast typing collapses into one
//     send per pause.
//   * Explicit "Enter" button sends KEYCODE_ENTER (the soft-keyboard's
//     IME_ACTION_DONE) so that e.g. a YouTube search submits.
//   * Close = dismiss locally; we don't tell the TV to defocus, because
//     there is no such message and the TV will simply re-push ImeKeyInject
//     the next time focus changes anyway.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidTvImeSheet(
    prompt: AndroidTvImePrompt,
    onTextChange: (String) -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Seed once per sheet-open. We deliberately do NOT re-seed when
    // `prompt.value` updates after we've sent edits, because the session
    // optimistically updates prompt.value to mirror what we just sent; if
    // we re-seeded on every prompt change the cursor would jump to the
    // TV's reported position mid-typing.
    var tfv by remember {
        val sel = prompt.selectionStart.coerceIn(0, prompt.value.length)
        val selEnd = prompt.selectionEnd.coerceIn(sel, prompt.value.length)
        mutableStateOf(TextFieldValue(prompt.value, TextRange(sel, selEnd)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Debounced live send. The `if` skips re-sending after our own
    // optimistic update has already aligned prompt.value with tfv.text —
    // without it, the next keystroke would still queue a redundant edit.
    LaunchedEffect(tfv.text) {
        if (tfv.text != prompt.value) {
            delay(50)
            onTextChange(tfv.text)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ImeSheetHeader(prompt = prompt, onDismiss = onDismiss)
            OutlinedTextField(
                value = tfv,
                onValueChange = { tfv = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = false,
                label = if (prompt.label.isNotEmpty()) {
                    { Text(prompt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else null,
                placeholder = { Text("Type here, sends as you type") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onEnter() },
                ),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onEnter) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Enter")
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun ImeSheetHeader(prompt: AndroidTvImePrompt, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = prompt.label.ifEmpty { "Type to TV" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = prompt.appLabel.ifEmpty { prompt.appPackage }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}
