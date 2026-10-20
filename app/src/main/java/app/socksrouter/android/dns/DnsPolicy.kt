package app.socksrouter.android.dns

enum class DnsFallbackPolicy { STRICT, AUTOMATIC }

sealed interface ResolverEndpoint {
    data class Doh(val url: String) : ResolverEndpoint
    data class Conventional(val address: String) : ResolverEndpoint
    data object System : ResolverEndpoint
}

data class DnsSettings(
    val dohUrl: String = BuiltInDohServices.services.first().url,
    val conventionalDns: String = "",
    // Public DoH can be intermittently unreachable on local networks. Keep
    // the configured priority, but fall back automatically so the first page
    // load is not lost to a transient DoH timeout.
    val fallbackPolicy: DnsFallbackPolicy = DnsFallbackPolicy.AUTOMATIC,
    val cacheMinutes: Int = 30,
) {
    fun resolverOrder(): List<ResolverEndpoint> {
        val configured = buildList {
            if (dohUrl.isNotBlank()) add(ResolverEndpoint.Doh(dohUrl.trim()))
            if (conventionalDns.isNotBlank()) add(ResolverEndpoint.Conventional(conventionalDns.trim()))
            add(ResolverEndpoint.System)
        }
        return if (fallbackPolicy == DnsFallbackPolicy.STRICT) configured.take(1) else configured
    }

    fun validationMessage(): String? = when {
        cacheMinutes !in 1..1440 -> "DNS 缓存时间必须在 1 到 1440 分钟之间"
        dohUrl.isNotBlank() && !dohUrl.startsWith("https://") -> "DoH 地址必须以 https:// 开头"
        conventionalDns.isNotBlank() && !IPV4.matches(conventionalDns.trim()) -> "请输入有效的 DNS IPv4 地址"
        else -> null
    }

    private companion object {
        val IPV4 = Regex("^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
    }
}

data class BuiltInDohService(val name: String, val url: String)

object BuiltInDohServices {
    val services = listOf(
        BuiltInDohService("Cloudflare", "https://1.1.1.1/dns-query"),
        BuiltInDohService("Google", "https://dns.google/dns-query"),
        BuiltInDohService("Quad9", "https://dns.quad9.net/dns-query"),
    )
}
