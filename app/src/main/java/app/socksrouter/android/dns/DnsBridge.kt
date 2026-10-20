package app.socksrouter.android.dns

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Synchronous bridge called by the native mapped-DNS callback. */
object DnsBridge {
    private const val TIMEOUT_MS = 4_000
    @Volatile private var settings = DnsSettings()
    @Volatile private var proxyAddress = ""
    @Volatile private var proxyPort = 0
    @Volatile private var cache = DnsCache({ settings.cacheMinutes * 60_000L })

    fun configure(newSettings: DnsSettings, serverAddress: String, serverPort: Int) {
        settings = newSettings
        proxyAddress = serverAddress
        proxyPort = serverPort
        cache = DnsCache({ settings.cacheMinutes * 60_000L })
    }

    fun clearCache() = cache.clear()

    @JvmStatic
    fun resolve(query: ByteArray): Boolean {
        val question = parseQuestion(query) ?: return false
        val key = "${question.name}/${question.type}"
        val cached = cache.get(key)
        if (cached != null) return DnsMessage.isSuccessful(cached)

        for (endpoint in settings.resolverOrder()) {
            val response = runCatching { query(endpoint, query, question.name) }.getOrNull() ?: continue
            if (DnsMessage.isResponse(response) && DnsMessage.isAuthoritativeNegative(response)) {
                DnsMessage.ttlMillis(response)?.let { cache.putNegative(key, response, it) }
                return false
            }
            if (DnsMessage.isSuccessful(response)) {
                DnsMessage.ttlMillis(response)?.let { cache.putPositive(key, response, it) }
                return true
            }
        }
        return false
    }

    private fun query(endpoint: ResolverEndpoint, query: ByteArray, name: String): ByteArray = when (endpoint) {
        is ResolverEndpoint.Doh -> doh(endpoint.url, query)
        is ResolverEndpoint.Conventional -> dnsTcp(endpoint.address, query)
        ResolverEndpoint.System -> {
            InetAddress.getByName(name)
            query // mapped DNS only needs a successful resolution signal
        }
    }

    private fun doh(endpoint: String, query: ByteArray): ByteArray {
        val connection = URL(endpoint).openConnection(socksProxy()) as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/dns-message")
        connection.setRequestProperty("Content-Type", "application/dns-message")
        connection.outputStream.use { it.write(query) }
        if (connection.responseCode !in 200..299) error("DoH HTTP ${connection.responseCode}")
        return connection.inputStream.use { it.readBytes() }
    }

    private fun dnsTcp(address: String, query: ByteArray): ByteArray {
        Socket(socksProxy()).use { socket ->
            socket.connect(InetSocketAddress(address, 53), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            DataOutputStream(socket.getOutputStream()).use { out ->
                out.writeShort(query.size)
                out.write(query)
                out.flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            return ByteArray(length).also { input.readFully(it) }
        }
    }

    private fun socksProxy(): Proxy = Proxy(
        Proxy.Type.SOCKS,
        InetSocketAddress(proxyAddress, proxyPort),
    )

    private data class Question(val name: String, val type: Int)

    private fun parseQuestion(packet: ByteArray): Question? {
        if (packet.size < 17) return null
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < packet.size) {
            val length = packet[offset++].toInt() and 0xff
            if (length == 0) break
            if (length > 63 || offset + length > packet.size) return null
            labels += packet.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
        }
        if (labels.isEmpty() || offset + 4 > packet.size) return null
        val type = ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
        return Question(labels.joinToString(".").lowercase(), type)
    }

}
