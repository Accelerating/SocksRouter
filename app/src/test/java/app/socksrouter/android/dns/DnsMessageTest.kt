package app.socksrouter.android.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {
    @Test
    fun extractsSmallestAnswerTtl() {
        val response = dnsResponse(answerCount = 2).apply {
            addAnswer(60)
            addAnswer(15)
        }.build()

        assertTrue(DnsMessage.isResponse(response))
        assertTrue(DnsMessage.isSuccessful(response))
        assertEquals(15_000L, DnsMessage.ttlMillis(response))
    }

    @Test
    fun recognizesNodataAsAuthoritativeNegative() {
        val response = dnsResponse(answerCount = 0).build()

        assertTrue(DnsMessage.isResponse(response))
        assertTrue(DnsMessage.isAuthoritativeNegative(response))
        assertNull(DnsMessage.ttlMillis(response))
    }

    @Test
    fun queryPacketIsNotTreatedAsDnsResponse() {
        val query = dnsResponse(answerCount = 0).also { it.flags = 0 }.build()

        assertFalse(DnsMessage.isResponse(query))
    }

    private class DnsPacketBuilder(private val answerCount: Int) {
        var flags = 0x8180
        private val bytes = mutableListOf<Byte>()

        init {
            addHeader()
            addQuestion()
        }

        fun addAnswer(ttlSeconds: Int) {
            bytes += listOf(0xc0, 0x0c, 0, 1, 0, 1,
                (ttlSeconds ushr 24) and 0xff, (ttlSeconds ushr 16) and 0xff,
                (ttlSeconds ushr 8) and 0xff, ttlSeconds and 0xff,
                0, 4, 1, 2, 3, 4).map(Int::toByte)
        }

        fun build(): ByteArray {
            bytes[2] = (flags ushr 8).toByte()
            bytes[3] = flags.toByte()
            return bytes.toByteArray()
        }

        private fun addHeader() {
            bytes += ByteArray(12).toList()
            bytes[4] = 0
            bytes[5] = 1
            bytes[6] = (answerCount ushr 8).toByte()
            bytes[7] = answerCount.toByte()
        }

        private fun addQuestion() {
            bytes += listOf(3, 'w'.code, 'w'.code, 'w'.code, 7,
                'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code,
                0, 0, 1, 0, 1).map(Int::toByte)
        }
    }

    private fun dnsResponse(answerCount: Int) = DnsPacketBuilder(answerCount)
}
