package app.socksrouter.android.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCacheTest {
    @Test fun configuredLifetimeCapsAnswerTtl() {
        var now = 0L
        val cache = DnsCache({ 30 * 60 * 1000L }, { now })
        cache.putPositive("a.example/A", byteArrayOf(1), 60 * 60 * 1000L)
        now = 29 * 60 * 1000L
        assertArrayEquals(byteArrayOf(1), cache.get("a.example/A"))
        now = 30 * 60 * 1000L
        assertNull(cache.get("a.example/A"))
    }

    @Test fun answerTtlCanExpireBeforeConfiguredLifetime() {
        var now = 0L
        val cache = DnsCache({ 30 * 60 * 1000L }, { now })
        cache.putPositive("a.example/A", byteArrayOf(1), 10_000)
        now = 10_000
        assertNull(cache.get("a.example/A"))
    }

    @Test fun negativeAnswersAreCappedAtFiveMinutes() {
        var now = 0L
        val cache = DnsCache({ 30 * 60 * 1000L }, { now })
        cache.putNegative("missing.example/A", byteArrayOf(3), 60 * 60 * 1000L)
        now = 5 * 60 * 1000L
        assertNull(cache.get("missing.example/A"))
        assertEquals(0, cache.size())
    }

    @Test fun clearRemovesEntriesImmediately() {
        val cache = DnsCache({ 30 * 60 * 1000L })
        cache.putPositive("a.example/A", byteArrayOf(1), 60_000)
        cache.clear()
        assertNull(cache.get("a.example/A"))
    }
}
