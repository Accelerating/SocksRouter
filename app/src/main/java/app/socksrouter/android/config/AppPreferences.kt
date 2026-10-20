package app.socksrouter.android.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import app.socksrouter.android.dns.DnsFallbackPolicy
import app.socksrouter.android.dns.DnsSettings

enum class RoutingMode {
    SELECTED_PROXY,
    SELECTED_DIRECT,
}

data class SavedConfig(
    val serverAddress: String = "10.200.0.24",
    val serverPort: String = "7897",
    val routingMode: RoutingMode = RoutingMode.SELECTED_PROXY,
    val selectedPackages: Set<String> = emptySet(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val activeProfileId: String = "",
    val dnsSettings: DnsSettings = DnsSettings(),
)

data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int,
)

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("router_config", Context.MODE_PRIVATE)

    fun load(): SavedConfig {
        val legacyAddress = preferences.getString(KEY_SERVER, null) ?: "10.200.0.24"
        val legacyPort = preferences.getString(KEY_PORT, null) ?: "7897"
        val profiles = readProfiles().ifEmpty {
            listOf(ServerProfile(id = "default", name = "默认服务器", address = legacyAddress, port = legacyPort.toIntOrNull() ?: 7897))
        }
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        val active = profiles.first { it.id == activeId }
        return SavedConfig(
        serverAddress = active.address,
        serverPort = active.port.toString(),
        routingMode = runCatching {
            RoutingMode.valueOf(preferences.getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(RoutingMode.SELECTED_PROXY),
        selectedPackages = preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet(),
        serverProfiles = profiles,
        activeProfileId = activeId,
        dnsSettings = DnsSettings(
            dohUrl = preferences.getString(KEY_DOH_URL, DnsSettings().dohUrl).orEmpty(),
            conventionalDns = preferences.getString(KEY_DNS_SERVER, "").orEmpty(),
            fallbackPolicy = runCatching {
                DnsFallbackPolicy.valueOf(preferences.getString(KEY_DNS_FALLBACK, "").orEmpty())
            }.getOrDefault(DnsFallbackPolicy.AUTOMATIC),
            cacheMinutes = preferences.getInt(KEY_DNS_CACHE_MINUTES, 30),
        ),
        )
    }

    fun save(config: SavedConfig) {
        preferences.edit()
            .putString(KEY_SERVER, config.serverAddress)
            .putString(KEY_PORT, config.serverPort)
            .putString(KEY_MODE, config.routingMode.name)
            .putStringSet(KEY_PACKAGES, config.selectedPackages)
            .putString(KEY_ACTIVE_PROFILE, config.activeProfileId)
            .putString(KEY_PROFILES, writeProfiles(config.serverProfiles))
            .putString(KEY_DOH_URL, config.dnsSettings.dohUrl)
            .putString(KEY_DNS_SERVER, config.dnsSettings.conventionalDns)
            .putString(KEY_DNS_FALLBACK, config.dnsSettings.fallbackPolicy.name)
            .putInt(KEY_DNS_CACHE_MINUTES, config.dnsSettings.cacheMinutes)
            .apply()
    }

    private fun readProfiles(): List<ServerProfile> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PROFILES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val port = item.getInt("port")
                if (port in 1..65535) add(
                    ServerProfile(item.getString("id"), item.getString("name"), item.getString("address"), port)
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeProfiles(profiles: List<ServerProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("address", profile.address)
                put("port", profile.port)
            })
        }
    }.toString()

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_PORT = "port"
        const val KEY_MODE = "routing_mode"
        const val KEY_PACKAGES = "selected_packages"
        const val KEY_PROFILES = "server_profiles"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_DOH_URL = "doh_url"
        const val KEY_DNS_SERVER = "dns_server"
        const val KEY_DNS_FALLBACK = "dns_fallback"
        const val KEY_DNS_CACHE_MINUTES = "dns_cache_minutes"
    }
}
