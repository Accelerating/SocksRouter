package app.socksrouter.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.socksrouter.android.MainActivity
import app.socksrouter.android.R
import app.socksrouter.android.config.RoutingMode
import app.socksrouter.android.diagnostics.DiagnosticLogStore
import app.socksrouter.android.dns.DnsBridge
import app.socksrouter.android.dns.DnsFallbackPolicy
import app.socksrouter.android.dns.DnsSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SocksVpnService : VpnService() {
    /** Guards the asynchronous native shutdown from duplicate stop actions. */
    private val stopInProgress = AtomicBoolean(false)
    private val networkRestartPending = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val underlyingNetworks = mutableSetOf<Network>()
    private val networkLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeConfigFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkMonitoring()
        DiagnosticLogStore.info("VPN 服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> startTunnel(intent)
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        DiagnosticLogStore.info("VPN 服务已销毁")
        unregisterNetworkMonitoring()
        // If Android destroys the service independently (for example after a VPN
        // revoke), still tear down the native tunnel.  Do not call stop twice when
        // the explicit stop path is already doing the same work on its worker.
        if (stopInProgress.compareAndSet(false, true)) {
            runCatching {
                if (HevTunnel.isRunning()) HevTunnel.stop()
            }.onFailure { Log.w(TAG, "native tunnel cleanup failed", it) }
        }
        closeVpnInterface()
        super.onDestroy()
    }

    private fun startTunnel(intent: Intent) {
        if (stopInProgress.get()) {
            Log.i(TAG, "start ignored while shutdown is in progress")
            DiagnosticLogStore.warning("忽略启动请求：停止流程仍在进行")
            return
        }
        // Android starts a ten-second deadline as soon as startForegroundService is called.
        // Enter foreground before parsing any untrusted Intent extras or touching JNI.
        startForeground(NOTIFICATION_ID, runningNotification())

        val config = PrototypeConfig(
            serverAddress = intent.getStringExtra(EXTRA_SERVER).orEmpty(),
            serverPort = intent.getIntExtra(EXTRA_PORT, 0),
            allowedPackages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toSet().orEmpty(),
            routingMode = runCatching {
                RoutingMode.valueOf(intent.getStringExtra(EXTRA_ROUTING_MODE).orEmpty())
            }.getOrDefault(RoutingMode.SELECTED_PROXY),
            dnsSettings = DnsSettings(
                dohUrl = intent.getStringExtra(EXTRA_DOH_URL) ?: DnsSettings().dohUrl,
                conventionalDns = intent.getStringExtra(EXTRA_DNS_SERVER).orEmpty(),
                fallbackPolicy = runCatching {
                    DnsFallbackPolicy.valueOf(intent.getStringExtra(EXTRA_DNS_FALLBACK).orEmpty())
                }.getOrDefault(DnsFallbackPolicy.AUTOMATIC),
                cacheMinutes = intent.getIntExtra(EXTRA_DNS_CACHE_MINUTES, 30),
            ),
        )
        if (config.validationMessage() != null) {
            DiagnosticLogStore.warning("启动失败：配置校验未通过")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        synchronized(lifecycleLock) {
            if (HevTunnel.isRunning()) HevTunnel.stop()
        }
        closeVpnInterface()
        networkRestartPending.set(false)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("198.18.0.2")
            .addAddress("fd00::1", 128)
            .addRoute("::", 0)
            // hev-socks5-tunnel schedules TUN I/O and SOCKS sessions cooperatively.
            // A blocking TUN read prevents the session tasks from processing replies.
            .setBlocking(false)

        // The app itself remains outside the VPN, preventing the native SOCKS socket from looping.
        when (config.routingMode) {
            RoutingMode.SELECTED_PROXY, RoutingMode.SELECTED_DIRECT -> {
                config.allowedPackages.forEach { selectedPackage ->
                    try {
                        if (config.routingMode == RoutingMode.SELECTED_PROXY) {
                            builder.addAllowedApplication(selectedPackage)
                        } else {
                            builder.addDisallowedApplication(selectedPackage)
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                        // An app may have been uninstalled between selection and tunnel startup.
                    }
                }
            }
            RoutingMode.GLOBAL_PROXY -> Unit // Capture every app regardless of saved selections.
        }
        if (config.routingMode != RoutingMode.SELECTED_PROXY) {
            builder.addDisallowedApplication(packageName)
        }

        val tun = builder.establish()
        if (tun == null) {
            DiagnosticLogStore.error("启动失败：系统未能创建 VPN 接口")
            stopTunnel()
            return
        }
        val configFile = File(filesDir, "prototype-hev.yml")
        configFile.writeText(hevConfig(config))
        DnsBridge.configure(config.dnsSettings, config.serverAddress, config.serverPort)
        // Keep the original descriptor in the service so the same VPN interface
        // can survive a temporary network loss. The native core gets a duplicate
        // and owns/closes that duplicate when its session ends.
        val nativeTun = runCatching { ParcelFileDescriptor.dup(tun.fileDescriptor) }.getOrElse {
            tun.close()
            DiagnosticLogStore.error("启动失败：VPN 接口复制失败")
            publishState(STATE_FAILED, "VPN 接口复制失败")
            stopTunnel()
            return
        }
        synchronized(lifecycleLock) {
            vpnInterface = tun
            activeConfigFile = configFile
        }
        val tunFd = nativeTun.detachFd()
        val started = HevTunnel.start(configFile.absolutePath, tunFd)
        if (!started) {
            // native_start_service may reject the request before taking ownership
            // of the descriptor (for example if a tunnel is already running).
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            closeVpnInterface()
            DiagnosticLogStore.error("启动失败：SOCKS5 隧道未能启动")
            publishState(STATE_FAILED, "SOCKS5 隧道启动失败")
            stopTunnel()
        } else {
            if (hasUnderlyingNetwork()) {
                DiagnosticLogStore.info("代理隧道已启动")
                publishState(STATE_RUNNING, "代理运行中")
            } else {
                networkRestartPending.set(true)
                DiagnosticLogStore.warning("代理已启动，但当前没有可用网络")
                publishState(STATE_WAITING_FOR_NETWORK, "等待网络；代理应用保持断网")
            }
        }
    }

    private fun registerNetworkMonitoring() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasEmpty = synchronized(networkLock) {
                    val wasEmpty = underlyingNetworks.isEmpty()
                    underlyingNetworks += network
                    wasEmpty
                }
                if (wasEmpty && !stopInProgress.get()) {
                    DnsBridge.clearCache()
                    if (HevTunnel.isRunning()) {
                        networkRestartPending.set(false)
                        DiagnosticLogStore.info("底层网络已恢复")
                        publishState(STATE_RUNNING, "网络已恢复，代理运行中")
                    } else if (networkRestartPending.get()) {
                        DiagnosticLogStore.info("底层网络已恢复，开始重建代理隧道")
                        publishState(STATE_CONNECTING, "网络已恢复，正在重连")
                        Thread({ restartNativeTunnel() }, "socks-vpn-network-restart").start()
                    }
                }
            }

            override fun onLost(network: Network) {
                val isEmpty = synchronized(networkLock) {
                    underlyingNetworks -= network
                    underlyingNetworks.isEmpty()
                }
                if (isEmpty && !stopInProgress.get() && HevTunnel.isRunning() &&
                    networkRestartPending.compareAndSet(false, true)) {
                    DnsBridge.clearCache()
                    DiagnosticLogStore.warning("底层网络已断开，暂停代理隧道")
                    publishState(STATE_WAITING_FOR_NETWORK, "等待网络；代理应用保持断网")
                    Thread({ stopNativeForNetworkLoss() }, "socks-vpn-network-stop").start()
                }
            }
        }
        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "could not register underlying network callback", it)
        }
    }

    private fun unregisterNetworkMonitoring() {
        val callback = networkCallback ?: return
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        }.onFailure { Log.w(TAG, "could not unregister underlying network callback", it) }
        networkCallback = null
        synchronized(networkLock) { underlyingNetworks.clear() }
    }

    private fun hasUnderlyingNetwork(): Boolean = synchronized(networkLock) {
        underlyingNetworks.isNotEmpty()
    }

    private fun stopNativeForNetworkLoss() {
        synchronized(lifecycleLock) {
            if (!stopInProgress.get() && !hasUnderlyingNetwork() && HevTunnel.isRunning()) {
                runCatching { HevTunnel.stop() }
                    .onFailure { Log.w(TAG, "native tunnel stop after network loss failed", it) }
            }
        }
        // The network may have returned while the native join was in progress.
        if (hasUnderlyingNetwork()) {
            if (HevTunnel.isRunning()) networkRestartPending.set(false)
            else restartNativeTunnel()
        }
    }

    private fun restartNativeTunnel() {
        synchronized(lifecycleLock) {
            if (!networkRestartPending.get() || stopInProgress.get() ||
                !hasUnderlyingNetwork() || HevTunnel.isRunning()) return

            val tun = vpnInterface
            val configFile = activeConfigFile
            if (tun == null || configFile == null) {
                Log.w(TAG, "cannot restart native tunnel without an active VPN interface")
                DiagnosticLogStore.error("重连失败：VPN 接口已失效")
                publishState(STATE_FAILED, "VPN 接口已失效，请重新启动")
                networkRestartPending.set(false)
                return
            }

            val nativeTun = runCatching { ParcelFileDescriptor.dup(tun.fileDescriptor) }.getOrElse {
                Log.w(TAG, "could not duplicate VPN interface for reconnect", it)
                DiagnosticLogStore.error("重连失败：无法复制 VPN 接口")
                publishState(STATE_FAILED, "VPN 重连失败，请重新启动")
                return
            }
            val tunFd = nativeTun.detachFd()
            val started = HevTunnel.start(configFile.absolutePath, tunFd)
            if (!started) {
                runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                DiagnosticLogStore.error("重连失败：SOCKS5 隧道未能重启")
                publishState(STATE_FAILED, "VPN 重连失败，请重新启动")
                return
            }
            networkRestartPending.set(false)
            DiagnosticLogStore.info("代理隧道已从网络断开中恢复")
            publishState(STATE_RUNNING, "代理运行中")
            Log.i(TAG, "native tunnel reconnected after network recovery")
        }
    }

    private fun closeVpnInterface() {
        val tun = synchronized(lifecycleLock) {
            val current = vpnInterface
            vpnInterface = null
            activeConfigFile = null
            current
        }
        runCatching { tun?.close() }
            .onFailure { Log.w(TAG, "VPN interface close failed", it) }
    }

    private fun stopTunnel() {
        if (!stopInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "stop ignored: shutdown already in progress")
            DiagnosticLogStore.warning("忽略重复停止请求")
            return
        }

        // Remove our foreground notification before waiting for native lwIP/TUN
        // cleanup.  A blocked native join must never leave a stale notification.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        publishState(STATE_STOPPING, "正在停止")
        DiagnosticLogStore.info("开始停止代理隧道")
        Log.i(TAG, "stop requested; foreground notification removed")

        Thread({
            val nativeResult = runCatching {
                synchronized(lifecycleLock) {
                    if (HevTunnel.isRunning()) HevTunnel.stop() else true
                }
            }.onFailure { Log.w(TAG, "native tunnel stop failed", it) }.getOrDefault(false)
            Log.i(TAG, "native tunnel stop completed: $nativeResult")
            if (nativeResult) DiagnosticLogStore.info("代理隧道已停止")
            else DiagnosticLogStore.error("停止失败：原生隧道未正常退出")
            networkRestartPending.set(false)
            closeVpnInterface()
            publishState(STATE_STOPPED, "已停止")
            stopSelf()
            stopInProgress.set(false)
        }, "socks-vpn-stop").start()
    }

    private fun publishState(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_STATE_MESSAGE, message)
        })
    }

    private fun hevConfig(config: PrototypeConfig): String = """
        tunnel:
          mtu: 1500
          multi-queue: false
          ipv4: 198.18.0.1
          icmp: off
        socks5:
          address: ${config.serverAddress}
          port: ${config.serverPort}
          udp: 'tcp'
        mapdns:
          address: 198.18.0.2
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 4096
        misc:
          log-file: null
          log-level: warn
    """.trimIndent()

    private fun runningNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(getString(R.string.vpn_running))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            0,
            getString(R.string.vpn_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, SocksVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        const val ACTION_START = "app.socksrouter.android.action.START"
        const val ACTION_STOP = "app.socksrouter.android.action.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_ROUTING_MODE = "routing_mode"
        const val EXTRA_DOH_URL = "doh_url"
        const val EXTRA_DNS_SERVER = "dns_server"
        const val EXTRA_DNS_FALLBACK = "dns_fallback"
        const val EXTRA_DNS_CACHE_MINUTES = "dns_cache_minutes"
        const val ACTION_STATE_CHANGED = "app.socksrouter.android.action.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_STATE_MESSAGE = "state_message"
        const val STATE_RUNNING = "running"
        const val STATE_CONNECTING = "connecting"
        const val STATE_WAITING_FOR_NETWORK = "waiting_for_network"
        const val STATE_STOPPING = "stopping"
        const val STATE_STOPPED = "stopped"
        const val STATE_FAILED = "failed"
        private const val TAG = "SocksVpnService"
        private const val CHANNEL_ID = "socks_vpn"
        private const val NOTIFICATION_ID = 1
    }
}
