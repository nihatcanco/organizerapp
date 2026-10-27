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
import organizer.ai.AiException
import organizer.ai.ItemSuggestion
import java.util.Base64

/** The JSON endpoint behind the "Fill in with AI" button on the item form (see app.js). */
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
    // app.js puts it in the photo field, as if it had been chosen there.
    photo?.let { photo ->
        putJsonObject("photo") {
            put("type", photo.contentType)
            put("data", Base64.getEncoder().encodeToString(photo.bytes))
            put("source", photo.source)
        }
    }
}
