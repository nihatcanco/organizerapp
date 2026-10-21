package organizer

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import organizer.db.Database
import organizer.web.lanAddresses
import organizer.web.organizer
import java.nio.file.Path
import kotlin.io.path.Path

const val APP_NAME = "Organizer"

data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val databasePath: Path = Path("data", "organizer.db"),
    /** When set, every page asks for this password (HTTP Basic auth, any username). */
    val password: String? = null,
    /** Base URL encoded into QR labels, e.g. `http://192.168.1.20:8080`. Derived from the request when null. */
    val publicUrl: String? = null,
    /** Whether items can be filled in from photos with the built-in AI model. */
    val ai: Boolean = true,
    /** Where downloaded AI models are kept. */
    val modelsPath: Path = Path("data", "models"),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val port = value("ORGANIZER_PORT") ?: value("PORT")
            val databasePath = value("ORGANIZER_DB")?.let { Path(it) } ?: Path("data", "organizer.db")
            return AppConfig(
                host = value("ORGANIZER_HOST") ?: "0.0.0.0",
                port = port?.let { it.toIntOrNull() ?: error("ORGANIZER_PORT must be a number, got '$it'") } ?: 8080,
                databasePath = databasePath,
                password = value("ORGANIZER_PASSWORD"),
                publicUrl = value("ORGANIZER_PUBLIC_URL")?.trimEnd('/'),
                ai = value("ORGANIZER_AI")?.lowercase() !in setOf("off", "false", "no", "0"),
                modelsPath = value("ORGANIZER_MODELS")?.let { Path(it) } ?: databasePath.toAbsolutePath().parent.resolve("models"),
            )
        }
    }
}

fun main() {
    // Photos are decoded with Java's image classes; headless keeps them from starting a desktop UI.
    System.setProperty("java.awt.headless", "true")
    val config = AppConfig.fromEnv()
    val database = Database.open(config.databasePath)
    embeddedServer(Netty, port = config.port, host = config.host) {
        organizer(config, database)
        monitor.subscribe(ApplicationStarted) { logStartup(config, database) }
    }.start(wait = true)
}

private fun logStartup(config: AppConfig, database: Database) {
    val lines = buildList {
        add("$APP_NAME is running.")
        add("  On this computer: http://localhost:${config.port}")
        if (config.host == "0.0.0.0" || config.host == "::") {
            lanAddresses().forEach { add("  On your network:  http://$it:${config.port}") }
        }
        add("  Database:         ${database.path}")
        if (config.password != null) add("  Password protection is on.")
        if (!config.ai) add("  Reading photos with AI is off.")
    }
    LoggerFactory.getLogger("organizer").info(lines.joinToString("\n"))
}
