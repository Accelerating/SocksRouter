# Use Android VPN service for traffic capture

Socks Router will use Android's local `VpnService` interface to capture traffic from selected applications without requiring a rooted device. This displays Android's VPN indicator and prevents concurrent use with another VPN, but it provides the supported system boundary needed for reliable per-application routing to SOCKS5 or the ordinary network path.
