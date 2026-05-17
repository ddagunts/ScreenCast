package io.github.ddagunts.screencast.androidtv

// Given the TV's last-known text and the user's new phone-side text,
// produce one RemoteImeObject span replacement that, applied to `old`,
// yields `new`. Returns null when there is nothing to send.
//
// We use a common-prefix/common-suffix shrink rather than a full LCS
// diff: it is O(min(len)) and produces the *single* contiguous span that
// changed. Multi-region edits (e.g. paste + simultaneous delete elsewhere)
// will collapse into one larger span that subsumes both regions — fine,
// because the TV applies it atomically and the next push will sync state.
//
// Why one op and not a sequence: the canonical wild-firmware behaviour is
// "one RemoteEditInfo per RemoteImeBatchEdit". Multiple ops in a single
// batch parse, but at least one Google TV firmware applies only the last
// one — and queuing multiple batches per keystroke would race the TV's
// echo. One op per keystroke is the safe, debuggable shape.
//
// Span semantics (matching RemoteImeObject): `start` (inclusive) and
// `end` (exclusive) index into `old`; `value` is the replacement.
// Indices count Java chars (UTF-16 code units), which matches what the
// TV expects — its text field internally uses Java strings on the
// Android side.
fun computeImeDiff(old: String, new: String): RemoteImeObject? {
    if (old == new) return null
    val minLen = minOf(old.length, new.length)
    var prefix = 0
    while (prefix < minLen && old[prefix] == new[prefix]) prefix++
    // Don't let the suffix overlap the prefix on either string — if the
    // user just typed a character that happens to match a later one, we
    // must still treat it as an insert at `prefix`, not as a no-op.
    var suffix = 0
    while (
        suffix < minLen - prefix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) suffix++
    val start = prefix
    val end = old.length - suffix
    val value = new.substring(prefix, new.length - suffix)
    return RemoteImeObject(start = start, end = end, value = value)
}
