package app.socksrouter.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrototypeConfigTest {
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
