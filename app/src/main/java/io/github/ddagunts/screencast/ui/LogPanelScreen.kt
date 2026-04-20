package io.github.ddagunts.screencast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ddagunts.screencast.util.LogRepository
import kotlinx.coroutines.flow.runningFold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogPanelScreen() {
    val accumulated = remember {
        LogRepository.flow.runningFold(emptyList<LogRepository.Entry>()) { acc, e ->
            (acc + e).takeLast(1000)
        }
    }
    val entries by accumulated.collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()
    val df = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val ctx = LocalContext.current

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${entries.size} entries",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { copyLogsToClipboard(ctx, entries, df) }) {
                Text("Copy logs")
            }
        }
        LazyColumn(state = listState) {
            items(entries) { e ->
                // App uses a dark color scheme; picked from Material A100-A200 tints for
                // contrast against near-black backgrounds.
                val color = when (e.level) {
                    LogRepository.Level.E -> Color(0xFFFF5252)
                    LogRepository.Level.W -> Color(0xFFFFB74D)
                    LogRepository.Level.I -> Color(0xFF82B1FF)
                    LogRepository.Level.D -> Color(0xFFB0BEC5)
                }
                Text(
                    "${df.format(Date(e.ts))} [${e.level}] ${e.tag}: ${e.msg}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ClipboardManager.setPrimaryClip is safe from a foreground Activity on every
// supported SDK (26+) without a runtime permission. Android 12+ shows a system
// toast automatically on successful copy, so on newer devices we skip our own.
private fun copyLogsToClipboard(
    ctx: Context,
    entries: List<LogRepository.Entry>,
    df: SimpleDateFormat,
) {
    val text = buildString(entries.size * 80) {
        for (e in entries) {
            append(df.format(Date(e.ts))); append(' ')
            append('['); append(e.level.name); append("] ")
            append(e.tag); append(": "); append(e.msg); append('\n')
        }
    }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ScreenCast logs", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
        Toast.makeText(ctx, "${entries.size} log lines copied", Toast.LENGTH_SHORT).show()
    }
}
