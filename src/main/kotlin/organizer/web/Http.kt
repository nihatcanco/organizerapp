package organizer.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.accept
import io.ktor.server.request.isMultipart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import java.io.IOException

const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

class UploadTooLargeException : RuntimeException("Upload too large")

/**
 * Whether [error] comes from an upload over [MAX_UPLOAD_BYTES]. Ktor's multipart parser reports a part over its
 * formFieldLimit as an IOException ("... limit ... exceeded"). The parser runs in a coroutine of its own, so that error
 * fails the whole call: it reaches StatusPages, not the code reading the form.
 */
fun isUploadTooLarge(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is UploadTooLargeException || (it is IOException && it.message?.contains("limit", ignoreCase = true) == true)
}

/** Submitted form values, trimmed, with an empty string for anything missing. */
class FormFields(private val values: Map<String, List<String>>) {
    constructor(parameters: Parameters) : this(parameters.entries().associate { it.key to it.value })

    operator fun get(name: String): String = values[name]?.firstOrNull()?.trim()?.replace("\r\n", "\n").orEmpty()

    fun all(name: String): List<String> = values[name].orEmpty()

    fun has(name: String): Boolean = !values[name].isNullOrEmpty()
}

class Upload(val contentType: ContentType?, val bytes: ByteArray)

class Submission(val fields: FormFields, val files: Map<String, Upload>)

/** Reads a urlencoded or multipart form; empty file inputs are left out. */
suspend fun ApplicationCall.receiveSubmission(): Submission {
    if (!request.isMultipart()) return Submission(FormFields(receiveParameters()), emptyMap())
    val fields = mutableMapOf<String, MutableList<String>>()
    val files = mutableMapOf<String, Upload>()
    // A part over formFieldLimit fails the call with Ktor's own error; StatusPages tells it apart (see isUploadTooLarge).
    receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES).forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> fields.getOrPut(part.name.orEmpty()) { mutableListOf() }.add(part.value)
                is PartData.FileItem -> {
                    val bytes = part.provider().readBuffer(MAX_UPLOAD_BYTES + 1).readByteArray()
                    if (bytes.size > MAX_UPLOAD_BYTES) throw UploadTooLargeException()
                    if (bytes.isNotEmpty()) files[part.name.orEmpty()] = Upload(part.contentType, bytes)
                }
                else -> Unit
            }
        } finally {
            part.release()
        }
    }
    return Submission(FormFields(fields), files)
}

val ApplicationCall.id: Long get() = parameters["id"]?.toLongOrNull() ?: throw NotFoundException()

fun ApplicationCall.queryParam(name: String): String? = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() }

val ApplicationCall.wantsJson: Boolean get() = request.accept()?.contains("application/json") == true

/** Redirect after a POST, so reloading the page doesn't submit the form again. */
suspend fun ApplicationCall.seeOther(path: String) {
    response.header(HttpHeaders.Location, path)
    respond(HttpStatusCode.SeeOther)
}

/** Only follow local paths, never `//other.site` or full URLs. */
fun safeReturnPath(path: String?, fallback: String): String =
    path?.takeIf { it.startsWith("/") && !it.startsWith("//") && !it.startsWith("/\\") } ?: fallback
