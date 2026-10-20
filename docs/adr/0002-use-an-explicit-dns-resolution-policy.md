# Use an explicit DNS resolution policy

Socks Router will choose resolvers in the order configured DoH, configured conventional DNS, then system DNS, while allowing either strict failure or explicit automatic fallback. DNS uses SOCKS5 by default, because silent resolver fallback or direct DNS would improve availability at the cost of disclosing queries; users may deliberately enable fallback or direct DNS when local-network resolution matters more.
