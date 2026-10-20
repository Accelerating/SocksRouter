package app.socksrouter.android.vpn

enum class ConnectionPhase {
    STOPPED,
    NEEDS_PERMISSION,
    CONNECTING,
    RUNNING,
    WAITING_FOR_NETWORK,
    STOPPING,
    FAILED,
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.STOPPED,
    val message: String = "尚未启动",
    val activeServer: String? = null,
    val proxiedBytes: Long = 0,
)

sealed interface ConnectionEvent {
    data class StartRequested(val server: String) : ConnectionEvent
    data object PermissionRequired : ConnectionEvent
    data object PermissionGranted : ConnectionEvent
    data object TunnelStarted : ConnectionEvent
    data object NetworkLost : ConnectionEvent
    data object NetworkAvailable : ConnectionEvent
    data class ServerChanged(val server: String) : ConnectionEvent
    data class Failed(val reason: String) : ConnectionEvent
    data object ServiceCrashed : ConnectionEvent
    data object StopRequested : ConnectionEvent
    data object Stopped : ConnectionEvent
}

fun reduceConnection(state: ConnectionState, event: ConnectionEvent): ConnectionState = when (event) {
    is ConnectionEvent.StartRequested -> state.copy(
        phase = ConnectionPhase.CONNECTING,
        message = "正在创建安全通道",
        activeServer = event.server,
    )
    ConnectionEvent.PermissionRequired -> state.copy(
        phase = ConnectionPhase.NEEDS_PERMISSION,
        message = "需要系统 VPN 授权",
    )
    ConnectionEvent.PermissionGranted -> state.copy(
        phase = ConnectionPhase.CONNECTING,
        message = "正在连接 SOCKS5 服务器",
    )
    ConnectionEvent.TunnelStarted -> state.copy(
        phase = ConnectionPhase.RUNNING,
        message = "代理运行中",
    )
    ConnectionEvent.NetworkLost -> state.copy(
        phase = ConnectionPhase.WAITING_FOR_NETWORK,
        message = "等待网络；代理应用保持断网",
    )
    ConnectionEvent.NetworkAvailable -> state.copy(
        phase = ConnectionPhase.CONNECTING,
        message = "网络已恢复，正在重连",
    )
    is ConnectionEvent.ServerChanged -> state.copy(
        phase = ConnectionPhase.CONNECTING,
        message = "服务器已切换，正在重连",
        activeServer = event.server,
    )
    is ConnectionEvent.Failed -> state.copy(
        phase = ConnectionPhase.FAILED,
        message = event.reason,
    )
    ConnectionEvent.ServiceCrashed -> state.copy(
        phase = ConnectionPhase.FAILED,
        message = "代理服务异常停止，流量已恢复系统网络",
    )
    ConnectionEvent.StopRequested -> state.copy(
        phase = ConnectionPhase.STOPPING,
        message = "正在停止",
    )
    ConnectionEvent.Stopped -> ConnectionState()
}
