package app.socksrouter.android.dns

/** Small DNS wire-format helpers used only for cache lifetime decisions. */
internal object DnsMessage {
    fun isResponse(packet: ByteArray): Boolean =
        packet.size >= HEADER_LENGTH && (packet[2].toInt() and 0x80) != 0

    fun isSuccessful(packet: ByteArray): Boolean =
        packet.size >= HEADER_LENGTH && (packet[3].toInt() and RCODE_MASK) == 0

    fun isAuthoritativeNegative(packet: ByteArray): Boolean {
        if (packet.size < HEADER_LENGTH) return false
        val rcode = packet[3].toInt() and RCODE_MASK
        val answerCount = readU16(packet, 6)
        return rcode == RCODE_NXDOMAIN || (rcode == 0 && answerCount == 0)
    }

    /** Returns the smallest answer/negative-authority TTL, or null if unavailable. */
    fun ttlMillis(packet: ByteArray): Long? {
        if (packet.size < HEADER_LENGTH) return null
        var offset = HEADER_LENGTH
        val questionCount = readU16(packet, 4)
        repeat(questionCount) {
            offset = skipName(packet, offset) ?: return null
            if (offset + 4 > packet.size) return null
            offset += 4
        }

        var minimumTtlSeconds: Long? = null
        val answerCount = readU16(packet, 6)
        val authorityCount = readU16(packet, 8)
        repeat(answerCount + authorityCount) { index ->
            offset = skipName(packet, offset) ?: return null
            if (offset + 10 > packet.size) return null
            val type = readU16(packet, offset)
            val ttlSeconds = readU32(packet, offset + 4)
            val dataLength = readU16(packet, offset + 8)
            offset += 10
            if (offset + dataLength > packet.size) return null

            // Positive answers use the answer section. Negative answers use the
            // SOA record in the authority section (type 6).
            if (index < answerCount || type == TYPE_SOA) {
                minimumTtlSeconds = minOf(minimumTtlSeconds ?: Long.MAX_VALUE, ttlSeconds)
            }
            offset += dataLength
        }
        return minimumTtlSeconds?.times(1_000L)
    }

    private fun skipName(packet: ByteArray, start: Int): Int? {
        var offset = start
        var jumps = 0
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xff
            when {
                length == 0 -> return offset + 1
                length and POINTER_MASK == POINTER_MASK ->
                    return if (offset + 1 < packet.size) offset + 2 else null
                length > MAX_LABEL_LENGTH || offset + 1 + length > packet.size -> return null
                else -> offset += 1 + length
            }
            if (++jumps > MAX_NAME_LABELS) return null
        }
        return null
    }

    private fun readU16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private fun readU32(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xff) shl 24) or
            ((packet[offset + 1].toLong() and 0xff) shl 16) or
            ((packet[offset + 2].toLong() and 0xff) shl 8) or
            (packet[offset + 3].toLong() and 0xff)

    private const val HEADER_LENGTH = 12
    private const val RCODE_MASK = 0x0f
    private const val RCODE_NXDOMAIN = 3
    private const val TYPE_SOA = 6
    private const val POINTER_MASK = 0xc0
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_LABELS = 128
}
