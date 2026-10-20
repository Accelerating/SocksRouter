package app.socksrouter.android.vpn

object HevTunnel {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    private external fun TProxyStartService(configPath: String, tunFd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    fun start(configPath: String, tunFd: Int): Boolean = TProxyStartService(configPath, tunFd)
    fun stop(): Boolean = TProxyStopService()
    fun isRunning(): Boolean = TProxyIsRunning()
    fun stats(): LongArray = TProxyGetStats()
}
