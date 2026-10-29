package organizer

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import organizer.db.Database
import organizer.web.lanAddresses
import organizer.web.organizer
import java.net.InetAddress
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path

const val APP_NAME = "Organizer"

data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Answer over HTTPS instead of HTTP, with a self-signed certificate (see Https.kt). */
    val https: Boolean = false,
    val databasePath: Path = Path("data", "organizer.db"),
    /** When set, every page asks for this password (HTTP Basic auth, any username). */
    val password: String? = null,
    /** Base URL encoded into QR labels, e.g. `http://192.168.1.20:8080`. Derived from the request when null. */
    val publicUrl: String? = null,
    /**
     * This computer's ID, which the saved API key's encryption is tied to (see store/SecretBox.kt). Found by the app
     * itself, except in Docker, whose containers can't see the computer: there Jenkins passes the host's ID.
     */
    val machineId: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val port = value("ORGANIZER_PORT") ?: value("PORT")
            val https = value("ORGANIZER_HTTPS")
            val databasePath = value("ORGANIZER_DB")?.let { Path(it) } ?: Path("data", "organizer.db")
            return AppConfig(
                host = value("ORGANIZER_HOST") ?: "0.0.0.0",
                port = port?.let { it.toIntOrNull() ?: error("ORGANIZER_PORT must be a number, got '$it'") } ?: 8080,
                https = https?.let { it.lowercase().toBooleanStrictOrNull() ?: error("ORGANIZER_HTTPS must be true or false, got '$it'") } ?: false,
                databasePath = databasePath,
                password = value("ORGANIZER_PASSWORD"),
                publicUrl = value("ORGANIZER_PUBLIC_URL")?.trimEnd('/'),
                machineId = value("ORGANIZER_MACHINE_ID"),
            )
        }
    }
}

fun main() {
    // Photos are decoded with Java's image classes; headless keeps them from starting a desktop UI.
    System.setProperty("java.awt.headless", "true")
    val config = AppConfig.fromEnv()
    val database = Database.open(config.databasePath)
    embeddedServer(Netty, configure = {
        // Browsers would use HTTP/2 over HTTPS, whose requests have no Host header, which the CSRF check needs.
        enableHttp2 = false
        if (config.https) {
            val keyStore = httpsKeyStore(config.databasePath.resolveSibling("https.p12"), httpsNames(config))
            val password = { HTTPS_KEY_STORE_PASSWORD.toCharArray() }
            sslConnector(keyStore, HTTPS_KEY_ALIAS, keyStorePassword = password, privateKeyPassword = password) {
                host = config.host
                port = config.port
            }
        } else {
            connector {
                host = config.host
                port = config.port
            }
        }
    }) {
        organizer(config, database)
        monitor.subscribe(ApplicationStarted) { logStartup(config, database) }
    }.start(wait = true)
}

/** The names the HTTPS certificate is for: this computer's, and the one in ORGANIZER_PUBLIC_URL. */
private fun httpsNames(config: AppConfig): Set<String> = buildSet {
    add("localhost")
    add("127.0.0.1")
    addAll(lanAddresses())
    // In a container this is the container's own name, which nobody uses.
    if (lanAddresses().isNotEmpty()) runCatching { add(InetAddress.getLocalHost().hostName) }
    config.publicUrl?.let { url -> runCatching { URI(url).host }.getOrNull()?.let(::add) }
}

private fun logStartup(config: AppConfig, database: Database) {
    val lines = buildList {
        add("$APP_NAME is running.")
        val scheme = if (config.https) "https" else "http"
        add("  On this computer: $scheme://localhost:${config.port}")
        if (config.host == "0.0.0.0" || config.host == "::") {
            lanAddresses().forEach { add("  On your network:  $scheme://$it:${config.port}") }
        }
        add("  Database:         ${database.path}")
        if (config.password != null) add("  Password protection is on.")
    }
    LoggerFactory.getLogger("organizer").info(lines.joinToString("\n"))
}
