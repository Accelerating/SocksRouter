package app.socksrouter.android.dns

class DnsCache(
    private val configuredLifetimeMillis: () -> Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val response: ByteArray, val expiresAt: Long)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun get(key: String): ByteArray? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= nowMillis()) {
            entries.remove(key)
            return null
        }
        return entry.response.copyOf()
    }

    @Synchronized
    fun putPositive(key: String, response: ByteArray, answerTtlMillis: Long) {
        val lifetime = minOf(configuredLifetimeMillis(), answerTtlMillis).coerceAtLeast(0)
        if (lifetime > 0) entries[key] = Entry(response.copyOf(), nowMillis() + lifetime)
    }

    @Synchronized
    fun putNegative(key: String, response: ByteArray, authoritativeTtlMillis: Long) {
        val lifetime = minOf(configuredLifetimeMillis(), authoritativeTtlMillis, MAX_NEGATIVE_MILLIS).coerceAtLeast(0)
        if (lifetime > 0) entries[key] = Entry(response.copyOf(), nowMillis() + lifetime)
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size

    private companion object { const val MAX_NEGATIVE_MILLIS = 5 * 60 * 1000L }
}
