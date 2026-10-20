package app.socksrouter.android.vpn

import app.socksrouter.android.config.RoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrototypeConfigTest {
    @Test
    fun globalProxyCanStartWithoutSelectingApplications() {
        val config = PrototypeConfig("192.168.1.2", 1080, routingMode = RoutingMode.GLOBAL_PROXY)

        assertNull(config.validationMessage())
    }

    @Test
    fun globalProxyAcceptsPreviouslySelectedApplications() {
        val config = PrototypeConfig(
            "192.168.1.2", 1080,
            allowedPackages = setOf("com.example.browser"),
            routingMode = RoutingMode.GLOBAL_PROXY,
        )

        assertNull(config.validationMessage())
    }

    @Test
    fun globalProxyStillRequiresValidServerConfiguration() {
        val config = PrototypeConfig("invalid", 1080, routingMode = RoutingMode.GLOBAL_PROXY)

        assertEquals("请输入有效的 SOCKS5 IPv4 地址", config.validationMessage())
        assertEquals(
            "端口必须在 1 到 65535 之间",
            config.copy(serverAddress = "192.168.1.2", serverPort = 0).validationMessage(),
        )
    }

    @Test
    fun selectedDirectStillRequiresAnApplicationSelection() {
        val config = PrototypeConfig("192.168.1.2", 1080, routingMode = RoutingMode.SELECTED_DIRECT)

        assertEquals("请至少选择一个需要直连的应用", config.validationMessage())
        assertNull(config.copy(allowedPackages = setOf("com.example.browser")).validationMessage())
    }

    @Test
    fun emptyApplicationSelectionIsRejectedBeforeStartingVpn() {
        val config = PrototypeConfig("192.168.1.2", 1080)

        assertEquals("请至少选择一个需要代理的应用", config.validationMessage())
    }

    @Test
    fun validPrototypeConfigurationCanStartVpn() {
        val config = PrototypeConfig(
            serverAddress = "192.168.1.2",
            serverPort = 1080,
            allowedPackages = setOf("com.example.browser"),
        )

        assertNull(config.validationMessage())
    }
}
