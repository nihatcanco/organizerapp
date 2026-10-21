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
import organizer.ai.PhotoReader
import organizer.db.Database
import organizer.store.ItemRepository
import organizer.store.LocationRepository
import java.security.MessageDigest

class Services(val config: AppConfig, val database: Database) {
    val locations = LocationRepository(database)
    val items = ItemRepository(database)
    val photoReader: PhotoReader? = if (config.ai) PhotoReader(config.modelsPath.resolve("florence-2-base-ft")) else null
}

private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; img-src 'self' data: blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"

fun Application.organizer(config: AppConfig, database: Database) {
    val app = Services(config, database)
    monitor.subscribe(ApplicationStopped) { app.photoReader?.close() }

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
            app.photoReader?.let { aiRoutes(it, app) }
        }
        if (password != null) authenticate("password", build = routes) else routes()
    }
}

private fun sameText(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
