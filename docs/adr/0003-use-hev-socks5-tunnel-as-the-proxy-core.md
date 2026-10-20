# Use hev-socks5-tunnel as the proxy core

Socks Router will use the MIT-licensed hev-socks5-tunnel core behind a Kotlin/JNI boundary instead of implementing a TCP/IP stack or adopting a larger multi-protocol engine. Its Android TUN integration, small native footprint, and SOCKS5 focus fit the product, while DNS policy remains owned by the application so DoH priority, explicit fallback, and caching are not coupled to the proxy core.
