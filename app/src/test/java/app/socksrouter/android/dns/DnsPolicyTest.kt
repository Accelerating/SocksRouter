package app.socksrouter.android.dns

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPolicyTest {
    @Test fun dohHasPriorityWithAutomaticFallback() {
        val settings = DnsSettings("https://dns.example/dns-query", "1.1.1.1", DnsFallbackPolicy.AUTOMATIC)
        assertEquals(
            listOf(ResolverEndpoint.Doh("https://dns.example/dns-query"), ResolverEndpoint.Conventional("1.1.1.1"), ResolverEndpoint.System),
            settings.resolverOrder(),
        )
    }

    @Test fun strictPolicyUsesOnlyHighestPriorityResolver() {
        val settings = DnsSettings("https://dns.example/dns-query", "1.1.1.1", DnsFallbackPolicy.STRICT)
        assertEquals(listOf(ResolverEndpoint.Doh("https://dns.example/dns-query")), settings.resolverOrder())
    }

    @Test fun systemDnsIsUsedWhenNothingIsConfigured() {
        assertEquals(listOf(ResolverEndpoint.System), DnsSettings(dohUrl = "").resolverOrder())
    }

    @Test fun cloudflareIsTheDefaultBuiltInDoh() {
        assertEquals("https://1.1.1.1/dns-query", DnsSettings().dohUrl)
    }

    @Test fun automaticFallbackIsTheDefaultPolicy() {
        assertEquals(DnsFallbackPolicy.AUTOMATIC, DnsSettings().fallbackPolicy)
        assertEquals(
            listOf(
                ResolverEndpoint.Doh("https://1.1.1.1/dns-query"),
                ResolverEndpoint.System,
            ),
            DnsSettings().resolverOrder(),
        )
    }

    @Test fun builtInDohServicesHaveHttpsEndpoints() {
        assertEquals(3, BuiltInDohServices.services.size)
        assert(BuiltInDohServices.services.all { it.url.startsWith("https://") })
    }
}
