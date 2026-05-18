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
import androidx.compose.material.icons.automirrored.filled.Backspace
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

// Phone-side keyboard buffer. The user types here; every change is fed
// back to the session as the new full-buffer string. The session computes
// the diff against what it last sent and emits one RemoteImeBatchEdit
// span replacement that, applied to the TV's view, yields the new
// buffer. Counters ride on every frame and are echo-driven (the canonical
// Android-TV-Remote-v2 mechanism, see AndroidTvSession.sendImeText).
//
// Design notes:
//   * Pre-populated from the TV's last-known field value so the user
//     starts editing what's actually on the TV (search query, etc.).
//   * 50 ms debounce: each new value cancels the previous send and
//     starts a fresh delay, so a burst of fast typing collapses into
//     one ImeBatchEdit per pause. Without this, multiple sends go out
//     before the TV's first counter echo arrives — they'd all carry
//     stale counters and the TV silently drops them. Matches what the
//     canonical Python library effectively gets via its async send_text
//     usage pattern.
//   * Explicit Backspace button as a fallback for IMEs that swallow the
//     hardware/soft backspace at end-of-buffer (some carrier IMEs do).
//     Wired through sendImeBackspace which emits an empty-value span
//     replacement of the trailing char — same wire mechanism as the
//     debounced text path, so the diff base stays consistent.
//   * Enter button + IME_ACTION_DONE both send KEYCODE_ENTER (via the
//     plain key-inject path) so search fields commit the query.
//   * Close = dismiss locally. There's no protocol message to tell the
//     TV "the user closed the keyboard"; the TV will re-push its IME
//     state on the next focus change anyway.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidTvImeSheet(
    prompt: AndroidTvImePrompt,
    onTextChange: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Seed once. We deliberately do NOT re-seed when `prompt.value`
    // updates after the user has started typing — the session is the
    // authority on what the TV has, so re-seeding from a stale push
    // would clobber the user's typing.
    var tfv by remember {
        val sel = prompt.selectionStart.coerceIn(0, prompt.value.length)
        val selEnd = prompt.selectionEnd.coerceIn(sel, prompt.value.length)
        mutableStateOf(TextFieldValue(prompt.value, TextRange(sel, selEnd)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Debounced live send. Every keystroke cancels the previous launch
    // and starts a new 50 ms delay, so a burst of fast typing collapses
    // into one send per pause. The session deduplicates against its own
    // lastSentText, so an initial-frame call with the seed value is a
    // harmless no-op.
    LaunchedEffect(tfv.text) {
        delay(50)
        onTextChange(tfv.text)
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
                onValueChange = { next -> tfv = next },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = false,
                label = if (prompt.label.isNotEmpty()) {
                    { Text(prompt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else null,
                placeholder = { Text("Type — each character is sent as a key press") },
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    // Optimistically drop the trailing char on our side
                    // so the TextField shows the deletion immediately;
                    // the session will emit the matching KEYCODE_DEL.
                    val text = tfv.text
                    if (text.isNotEmpty()) {
                        val dropped = text.dropLast(1)
                        tfv = TextFieldValue(dropped, TextRange(dropped.length))
                    }
                    onBackspace()
                }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Backspace")
                }
                Spacer(Modifier.size(0.dp))
                FilledTonalButton(onClick = onEnter, modifier = Modifier) {
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
