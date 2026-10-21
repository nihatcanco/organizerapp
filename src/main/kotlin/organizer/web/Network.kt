package organizer.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import organizer.AppConfig
import java.net.Inet4Address
import java.net.NetworkInterface

/** This computer's addresses on the local network (e.g. 192.168.1.20), Wi-Fi/Ethernet first. */
fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.networkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .sortedBy { interfacePriority(it.name) }
        .flatMap { networkInterface ->
            networkInterface.inetAddresses().toList()
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress }
        }
        .distinct()
}.getOrDefault(emptyList())

// VM, container and VPN adapters also have private addresses, but phones can't usually reach them.
private fun interfacePriority(name: String): Int = when {
    listOf("en", "eth", "wlan", "wl").any { name.startsWith(it) } -> 0
    listOf("bridge", "docker", "veth", "vmnet", "vbox", "utun", "tun", "tap", "br-").any { name.startsWith(it) } -> 2
    else -> 1
}

/**
 * The address other devices should use to reach this app, for QR labels. When the page was opened
 * via localhost, that would be useless on a phone, so the computer's network address is used instead.
 */
fun ApplicationCall.publicBaseUrl(config: AppConfig): String {
    config.publicUrl?.let { return it }
    val scheme = request.origin.scheme
    val host = request.host().let { if (isLoopback(it)) lanAddresses().firstOrNull() ?: it else it }
    val port = request.port()
    val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return "$scheme://$hostPart" + if (defaultPort) "" else ":$port"
}

fun isLoopback(host: String): Boolean =
    host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"
