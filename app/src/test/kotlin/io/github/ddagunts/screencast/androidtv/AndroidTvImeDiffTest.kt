package io.github.ddagunts.screencast.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTvImeDiffTest {

    // The behavioural contract: applying the returned span replacement to
    // `old` must yield `new`. We assert that directly so the test catches
    // both off-by-one cases and "the diff is correct but indexed against
    // the wrong base".
    private fun assertApplies(old: String, new: String) {
        val diff = computeImeDiff(old, new) ?: return assertEquals(old, new)
        val applied = old.substring(0, diff.start) + diff.value + old.substring(diff.end)
        assertEquals(new, applied)
    }

    @Test fun `no change returns null`() {
        assertNull(computeImeDiff("hello", "hello"))
        assertNull(computeImeDiff("", ""))
    }

    @Test fun `insert at end is append`() {
        val diff = computeImeDiff("hel", "hello")
        assertEquals(RemoteImeObject(start = 3, end = 3, value = "lo"), diff)
        assertApplies("hel", "hello")
    }

    @Test fun `insert at start is prepend`() {
        val diff = computeImeDiff("world", "hello world")
        assertEquals(RemoteImeObject(start = 0, end = 0, value = "hello "), diff)
        assertApplies("world", "hello world")
    }

    @Test fun `insert in the middle is span-insert with zero-width span`() {
        val diff = computeImeDiff("abef", "abcdef")
        assertEquals(RemoteImeObject(start = 2, end = 2, value = "cd"), diff)
        assertApplies("abef", "abcdef")
    }

    @Test fun `delete at end is empty-value tail span`() {
        val diff = computeImeDiff("hello", "hel")
        assertEquals(RemoteImeObject(start = 3, end = 5, value = ""), diff)
        assertApplies("hello", "hel")
    }

    @Test fun `delete at start is empty-value head span`() {
        val diff = computeImeDiff("hello world", "world")
        assertEquals(RemoteImeObject(start = 0, end = 6, value = ""), diff)
        assertApplies("hello world", "world")
    }

    @Test fun `delete in the middle is empty-value mid span`() {
        val diff = computeImeDiff("abXYef", "abef")
        assertEquals(RemoteImeObject(start = 2, end = 4, value = ""), diff)
        assertApplies("abXYef", "abef")
    }

    @Test fun `replace selection is span replace`() {
        // Don't pin the exact shape — multiple equivalent spans could
        // produce the same final string ("brave" inserted with the space
        // landing on either side). The application-equivalence assertion
        // is the real contract.
        assertApplies("hello world", "hello brave world")
    }

    @Test fun `full replace returns whole-string span`() {
        val diff = computeImeDiff("foo", "bar")
        assertEquals(RemoteImeObject(start = 0, end = 3, value = "bar"), diff)
        assertApplies("foo", "bar")
    }

    @Test fun `clear field returns full-span empty replacement`() {
        val diff = computeImeDiff("hello", "")
        assertEquals(RemoteImeObject(start = 0, end = 5, value = ""), diff)
        assertApplies("hello", "")
    }

    @Test fun `from empty inserts at zero`() {
        val diff = computeImeDiff("", "hi")
        assertEquals(RemoteImeObject(start = 0, end = 0, value = "hi"), diff)
        assertApplies("", "hi")
    }

    @Test fun `suffix shrink does not overlap prefix`() {
        // Both strings share the character 'a' at multiple positions —
        // the algorithm must not extend the suffix past the prefix
        // boundary, otherwise it would over-shrink and produce a
        // negative-length span.
        val diff = computeImeDiff("a", "aa")
        // "a" → "aa": one common 'a' at start, then we insert another
        // 'a' at the end. Application equivalence is what matters.
        assertApplies("a", "aa")
        assertEquals(1, diff?.value?.length)
    }

    @Test fun `suffix and prefix dont overlap on repeated chars`() {
        // "aaaa" -> "aa": deleting two 'a's. The naive "extend prefix
        // until first mismatch, then extend suffix until first mismatch"
        // would walk the prefix through all 2 chars of new, then walk
        // the suffix through 2 more — which is a 4-char suffix on a
        // 2-char string. The clamp keeps us honest.
        assertApplies("aaaa", "aa")
        val diff = computeImeDiff("aaaa", "aa")!!
        // Verify the span makes sense (start <= end, end <= oldLen).
        assert(diff.start <= diff.end)
        assert(diff.end <= 4)
    }
}
