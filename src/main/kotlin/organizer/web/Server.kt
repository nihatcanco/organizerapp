package organizer.web

import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.html.a
import kotlinx.html.p
import organizer.AppConfig
import org.slf4j.LoggerFactory
import organizer.ai.Ai
import organizer.ai.AnthropicClaude
import organizer.ai.Claude
import organizer.ai.PhotoFinder
import organizer.ai.WebPhotos
import organizer.db.Database
import organizer.store.ItemRepository
import organizer.store.LocationRepository
import organizer.store.SecretBox
import organizer.store.SettingsRepository
import organizer.store.UsageRepository
import organizer.store.findMachineId
import java.security.MessageDigest

class Services(val config: AppConfig, val database: Database, connect: (apiKey: String) -> Claude, photos: PhotoFinder) {
    val locations = LocationRepository(database)
    val items = ItemRepository(database)
    val ai: Ai

    init {
        // The key that encrypts the API key lives next to the database, but never inside it, so backups don't hold it.
        val machineId = config.machineId ?: findMachineId()
        if (machineId == null) {
            LoggerFactory.getLogger("organizer").warn(
                "No machine ID found, so a saved API key isn't tied to this computer. In Docker, set ORGANIZER_MACHINE_ID (see README.md).",
            )
        }
        val secrets = SecretBox(database.path.resolveSibling("secret.key"), machineId)
        ai = Ai(SettingsRepository(database), secrets, UsageRepository(database), connect, photos)
    }
}

private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; img-src 'self' data: blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"

/** The app; [connect] makes the client that talks to Claude and [photos] downloads item photos, which tests replace. */
fun Application.organizer(
    config: AppConfig,
    database: Database,
    photos: PhotoFinder = WebPhotos(),
    connect: (apiKey: String) -> Claude = ::AnthropicClaude,
) {
    val app = Services(config, database, connect, photos)
    monitor.subscribe(ApplicationStopped) { app.ai.close() }

    install(DefaultHeaders) {
        header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "same-origin")
    }
    // Answers HEAD requests, as uptime monitors send, like the GET they stand for.
    install(AutoHeadResponse)
    // Rejects form posts coming from other websites.
    install(CSRF) { originMatchesHost() }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ -> call.respondNotFound() }
        exception<NotFoundException> { call, _ -> call.respondNotFound() }
        exception<BadRequestException> { call, cause ->
            call.respondPage("Bad request", HttpStatusCode.BadRequest) {
                emptyState("That didn't work", cause.message ?: "The request was not valid.")
            }
        }
        exception<Throwable> { call, cause ->
            if (isUploadTooLarge(cause)) {
                call.respondPage("Photo too large", HttpStatusCode.PayloadTooLarge) {
                    emptyState("That photo is too large", "Photos can be up to ${MAX_UPLOAD_BYTES / 1024 / 1024} MB. Go back and pick a smaller one.") {
                        p { a("/", classes = "btn") { +"Home" } }
                    }
                }
                return@exception
            }
            call.application.log.error("Failed: ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respondPage("Error", HttpStatusCode.InternalServerError) {
                emptyState("Something went wrong", "The error has been logged. Please try again.")
            }
        }
    }

    val password = config.password
    if (password != null) {
        val guesses = PasswordGuesses()
        // An address that got the password wrong too often waits before it may try again, so it can't be guessed quickly.
        intercept(ApplicationCallPipeline.Plugins) {
            val wait = guesses.waitSeconds(call.request.origin.remoteAddress)
            if (wait > 0) {
                call.response.header(HttpHeaders.RetryAfter, wait)
                call.respondPage("Too many tries", HttpStatusCode.TooManyRequests) {
                    emptyState("Too many wrong passwords", "Wait a minute, then try again.")
                }
                finish()
            }
        }
        install(Authentication) {
            basic("password") {
                realm = "Organizer"
                validate { credentials ->
                    val address = request.origin.remoteAddress
                    if (sameText(credentials.password, password)) {
                        guesses.succeeded(address)
                        UserIdPrincipal(credentials.name)
                    } else {
                        guesses.failed(address)
                        null
                    }
                }
            }
        }
    }

    routing {
        val routes: Route.() -> Unit = {
            staticResources("/static", "static") {
                // Asset URLs carry a content hash (see Assets), so browsers may keep them for good.
                cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 3600)) }
            }
            homeRoutes(app)
            locationRoutes(app)
            itemRoutes(app)
            searchRoutes(app)
            labelRoutes(app)
            exportRoutes(app)
            settingsRoutes(app)
            aiRoutes(app)
        }
        if (password != null) authenticate("password", build = routes) else routes()
    }
}

private fun sameText(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

/**
 * Wrong passwords by address: one that got it wrong [MAX_WRONG] times within a minute waits until the oldest of those
 * is a minute old. Behind a reverse proxy every visitor has the proxy's address, so they wait together.
 */
internal class PasswordGuesses(private val now: () -> Long = System::currentTimeMillis) {
    private val wrong = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun failed(address: String) {
        forget()
        wrong.getOrPut(address) { ArrayDeque() }.addLast(now())
    }

    @Synchronized
    fun succeeded(address: String) {
        wrong.remove(address)
    }

    /** How long [address] has to wait before it may try again, in seconds; 0 when it may try now. */
    @Synchronized
    fun waitSeconds(address: String): Long {
        forget()
        val times = wrong[address]?.takeIf { it.size >= MAX_WRONG } ?: return 0
        // Requests sent at the same moment can all get it wrong, so there may be more than MAX_WRONG.
        return (times[times.size - MAX_WRONG] + WINDOW_MILLIS - now() + 999) / 1000
    }

    /** Drops wrong passwords from more than a minute ago. */
    private fun forget() {
        val cutoff = now() - WINDOW_MILLIS
        for (times in wrong.values) while (times.isNotEmpty() && times.first() <= cutoff) times.removeFirst()
        wrong.values.removeAll { it.isEmpty() }
    }

    companion object {
        const val MAX_WRONG = 10
        private const val WINDOW_MILLIS = 60_000L
    }
}
