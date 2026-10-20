# Socks Router

Socks Router lets a device owner decide which installed applications reach the network through a SOCKS5 server and which applications connect directly.

## Language

**SOCKS5 Server**:
An IPv4 endpoint on the local network that relays traffic for proxied applications. The initial product does not authenticate to it.
_Avoid_: Proxy node, VPN server

**Server Profile**:
A saved name, SOCKS5 Server address, and port that the user can select for a connection. Multiple profiles may be saved, but the product does not switch between them automatically.
_Avoid_: Proxy, node, server entry

**Active Server**:
The one Server Profile manually selected for the next connection or the current session.
_Avoid_: Default server, primary server

**DNS Cache**:
A memory-only store of recent DNS answers used to avoid repeating resolution requests. Its configurable duration, 30 minutes by default, is an upper bound on each answer's own TTL; relevant network or configuration changes clear the cache.
_Avoid_: DNS history

**Negative DNS Answer**:
An authoritative result that a requested domain does not exist. It may be cached for at most five minutes, unlike timeouts and temporary resolver failures, which are never cached.
_Avoid_: DNS error

**Routing Mode**:
The rule that determines whether the selected applications are proxied and all others connect directly, or the selected applications connect directly and all others are proxied.
_Avoid_: Whitelist mode, blacklist mode

**Selected Application**:
An installed application explicitly chosen in the application-routing screen. Its treatment depends on the current Routing Mode.
_Avoid_: Whitelisted app, excluded app

**Application Route Group**:
One or more installed packages that share an Android UID and therefore must receive the same routing treatment. A single-package group is presented as an ordinary application.
_Avoid_: App group, shared app

**Proxied Application**:
An application whose captured network traffic must use the SOCKS5 Server. If the server is unavailable, that traffic remains blocked rather than falling back to a direct connection.
_Avoid_: VPN app

**Direct Application**:
An application whose traffic bypasses the SOCKS5 Server and uses the device's ordinary network path.
_Avoid_: Bypassed app

**DNS Resolution Policy**:
The ordered choice used to resolve domain names: a configured DNS-over-HTTPS service first, otherwise a configured conventional DNS server, and otherwise the device's system DNS.
_Avoid_: DNS mode, DNS fallback list

**Strict DNS Fallback**:
A DNS failure policy that stops resolution when the highest-priority configured resolver fails instead of sending the query to a less private resolver.
_Avoid_: Secure DNS

**Automatic DNS Fallback**:
A DNS failure policy that tries each available resolver in DNS Resolution Policy order until one succeeds.
_Avoid_: Best-effort DNS

**Local Network Direct**:
An optional exception that sends private IPv4 unicast, link-local, multicast, and broadcast traffic directly even when it originates from a Proxied Application. It is disabled by default.
_Avoid_: LAN bypass, private bypass

**Public DoH Service**:
A DNS-over-HTTPS resolver reached over the public network with a certificate trusted by Android. Private-network DoH and bypassing certificate validation are outside the initial product.
_Avoid_: Secure DNS server

**Diagnostic Log**:
A short-lived record of connection state changes and error categories that excludes destination domains, destination addresses, and transferred content.
_Avoid_: Traffic log, browsing history
