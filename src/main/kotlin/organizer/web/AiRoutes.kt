package organizer.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import organizer.ai.ItemSuggestion
import organizer.ai.PhotoReader
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** JSON endpoints behind the "Fill in from photo" button on the item form (see app.js). */
fun Route.aiRoutes(reader: PhotoReader, app: Services) {
    get("/ai/status") { call.respondJson(reader.statusJson()) }

    post("/ai/download") {
        reader.startDownload()
        call.respondJson(reader.statusJson())
    }

    post("/ai/read") {
        if (reader.status() != PhotoReader.Status.Ready) {
            return@post call.respondJson(reader.statusJson(), HttpStatusCode.Conflict)
        }
        val submission = call.receiveSubmission()
        // A newly chosen photo, or the one already saved with the item being edited.
        val bytes = submission.files["photo"]?.bytes
            ?: submission.fields["item"].toLongOrNull()?.let { app.items.photo(it, thumbnail = false)?.bytes }
            ?: return@post call.respondError("Choose a photo first.", HttpStatusCode.BadRequest)
        val image = withContext(Dispatchers.IO) { ImageIO.read(ByteArrayInputStream(bytes)) }
            ?: return@post call.respondError("This kind of image can't be read here; JPEG and PNG work.", HttpStatusCode.UnsupportedMediaType)
        call.respondJson(reader.read(image).toJson())
    }
}

private suspend fun ApplicationCall.respondJson(json: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(message: String, status: HttpStatusCode) =
    respondJson(buildJsonObject { put("error", message) }, status)

private fun PhotoReader.statusJson() = buildJsonObject {
    when (val status = status()) {
        PhotoReader.Status.Missing -> put("state", "missing")
        PhotoReader.Status.Ready -> put("state", "ready")
        is PhotoReader.Status.Downloading -> {
            put("state", "downloading")
            put("done", status.done)
        }
        is PhotoReader.Status.Failed -> {
            put("state", "failed")
            put("error", status.message)
        }
    }
    put("total", totalBytes)
}

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
    putJsonArray("text") { text.forEach { add(it) } }
    put("caption", caption)
}
