package organizer.web

import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
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
        exception<UploadTooLargeException> { call, _ ->
            call.respondPage("Photo too large", HttpStatusCode.PayloadTooLarge) {
                emptyState("That photo is too large", "Photos can be up to ${MAX_UPLOAD_BYTES / 1024 / 1024} MB. Go back and pick a smaller one.") {
                    p { a("/", classes = "btn") { +"Home" } }
                }
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Failed: ${call.request.httpMethod.value} ${call.request.uri}", cause)
            call.respondPage("Error", HttpStatusCode.InternalServerError) {
                emptyState("Something went wrong", "The error has been logged. Please try again.")
            }
        }
    }

    val password = config.password
    if (password != null) {
        install(Authentication) {
            basic("password") {
                realm = "Organizer"
                validate { credentials -> if (sameText(credentials.password, password)) UserIdPrincipal(credentials.name) else null }
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
