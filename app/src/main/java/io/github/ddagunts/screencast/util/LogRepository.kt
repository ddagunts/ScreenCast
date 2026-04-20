package io.github.ddagunts.screencast.util

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LogRepository {
    enum class Level { D, I, W, E }
    data class Entry(val ts: Long, val level: Level, val tag: String, val msg: String)

    private val _flow = MutableSharedFlow<Entry>(replay = 500, extraBufferCapacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow = _flow.asSharedFlow()

    fun log(level: Level, tag: String, msg: String) {
        when (level) {
            Level.D -> Log.d(tag, msg)
            Level.I -> Log.i(tag, msg)
            Level.W -> Log.w(tag, msg)
            Level.E -> Log.e(tag, msg)
        }
        _flow.tryEmit(Entry(System.currentTimeMillis(), level, tag, msg))
    }
}

fun Any.logD(msg: String) = LogRepository.log(LogRepository.Level.D, javaClass.simpleName, redact(msg))
fun Any.logI(msg: String) = LogRepository.log(LogRepository.Level.I, javaClass.simpleName, redact(msg))
fun Any.logW(msg: String) = LogRepository.log(LogRepository.Level.W, javaClass.simpleName, redact(msg))
fun Any.logE(msg: String, t: Throwable? = null) =
    LogRepository.log(LogRepository.Level.E, javaClass.simpleName,
        redact(if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}"))

// Session/transport IDs and the per-session HTTP token are enough to hijack the
// cast while it's live — a user who shares logs for debugging shouldn't leak them.
private val SECRETS = listOf(
    Regex("""("(?:sessionId|transportId)"\s*:\s*")([^"]+)(")""") to """$1***$3""",
    Regex("""(/c/)([A-Za-z0-9_\-]+)(/)""") to """$1***$3""",
)

private fun redact(msg: String): String {
    var out = msg
    for ((re, repl) in SECRETS) out = re.replace(out, repl)
    return out
}
