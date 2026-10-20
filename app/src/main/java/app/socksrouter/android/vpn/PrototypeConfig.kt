package app.socksrouter.android.vpn

import app.socksrouter.android.config.RoutingMode
import app.socksrouter.android.dns.DnsSettings

data class PrototypeConfig(
    val serverAddress: String,
    val serverPort: Int,
    val allowedPackages: Set<String> = emptySet(),
    val routingMode: RoutingMode = RoutingMode.SELECTED_PROXY,
    val dnsSettings: DnsSettings = DnsSettings(),
) {
    fun isValid(): Boolean =
        IPV4.matches(serverAddress) && serverPort in 1..65535

    fun validationMessage(): String? = when {
        !IPV4.matches(serverAddress) -> "请输入有效的 SOCKS5 IPv4 地址"
        serverPort !in 1..65535 -> "端口必须在 1 到 65535 之间"
        allowedPackages.isEmpty() -> "请至少选择一个需要代理的应用"
        else -> null
    }

    companion object {
        private val IPV4 = Regex(
            "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)$"
        )
    }
}
