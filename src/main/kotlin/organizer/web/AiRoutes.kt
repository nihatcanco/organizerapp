package organizer.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import organizer.ai.Ai
import organizer.ai.AiException
import organizer.ai.ItemSuggestion
import java.net.URI
import java.util.Base64

/** The JSON endpoints behind the "Fill in with AI" and "Find photo" buttons on the item form (see app.js). */
fun Route.aiRoutes(app: Services) {
    // Claude gets the photo, the typed name, or both: a newly chosen photo, or the one saved with the item being edited.
    post("/ai/fill") {
        if (!app.ai.enabled()) {
            return@post call.respondError("Add an Anthropic API key in Settings first.", HttpStatusCode.Conflict)
        }
        val submission = call.receiveSubmission()
        val name = submission.fields["name"].take(200).ifEmpty { null }
        val photo = submission.files["photo"]?.bytes
            ?: submission.fields["item"].toLongOrNull()?.let { app.items.photo(it, thumbnail = false)?.bytes }
        if (photo == null && name == null) {
            return@post call.respondError("Type a name or choose a photo first.", HttpStatusCode.BadRequest)
        }
        try {
            call.respondJson(app.ai.fill(photo, name).toJson())
        } catch (e: AiException) {
            call.respondError("${e.message}.", HttpStatusCode.BadGateway)
        }
    }

    // A photo from the web, found by the item's typed name.
    post("/ai/photo") {
        if (!app.ai.enabled()) {
            return@post call.respondError("Add an Anthropic API key in Settings first.", HttpStatusCode.Conflict)
        }
        val name = call.receiveSubmission().fields["name"].take(200)
        if (name.isEmpty()) return@post call.respondError("Type the item's name first.", HttpStatusCode.BadRequest)
        try {
            call.respondJson(app.ai.findPhoto(name).toJson())
        } catch (e: AiException) {
            call.respondError("${e.message}.", HttpStatusCode.BadGateway)
        }
    }
}

private suspend fun ApplicationCall.respondJson(json: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(message: String, status: HttpStatusCode) =
    respondJson(buildJsonObject { put("error", message) }, status)

private fun ItemSuggestion.toJson() = buildJsonObject {
    name?.let { put("name", it) }
    quantity?.let { put("quantity", it) }
    putJsonArray("tags") { tags.forEach { add(it) } }
    putJsonArray("properties") {
        for (property in properties) {
            addJsonObject {
                put("name", property.name)
                put("value", property.value)
            }
        }
    }
    notes?.let { put("notes", it) }
}

/** The photo, which app.js puts in the photo field as if it had been chosen there, or a "note" on why there's none. */
private fun Ai.PhotoSearch.toJson() = buildJsonObject {
    val photo = photo
    if (photo != null) {
        putJsonObject("photo") {
            put("type", photo.contentType)
            put("data", Base64.getEncoder().encodeToString(photo.bytes))
            put("source", photo.source)
        }
    } else {
        val sites = pages.mapNotNull { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() }.distinct()
        put(
            "note",
            if (sites.isEmpty()) "Claude found no page with a photo of this exact item."
            else "Couldn't download a photo from ${sites.joinToString(", ")}; Settings › Logs says why.",
        )
    }
}
