package organizer.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.deleteIfExists

fun Route.exportRoutes(app: Services) {
    get("/export/items.csv") {
        val tree = app.locations.tree()
        val items = app.items.all()
        val csv = buildString {
            append('﻿') // lets Excel recognise the file as UTF-8
            appendCsvRow("id", "name", "quantity", "warn_at", "location", "tags", "properties", "notes", "created", "updated")
            for (item in items) {
                appendCsvRow(
                    item.id.toString(),
                    item.name,
                    item.quantity.toString(),
                    item.minQuantity?.toString().orEmpty(),
                    item.locationId?.let(tree::pathName).orEmpty(),
                    item.tags.joinToString(", "),
                    item.properties.joinToString("; ") { "${it.name}: ${it.value}" },
                    item.notes,
                    localTime(item.createdAt),
                    localTime(item.updatedAt),
                )
            }
        }
        call.downloadAs("organizer-items-${fileStamp()}.csv")
        call.respondText(csv, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }

    // A complete copy of the database, photos included. Restore by replacing the .db file.
    get("/export/backup") {
        val file = Files.createTempFile("organizer-backup-", ".db")
        try {
            app.database.backupTo(file)
            call.downloadAs("organizer-backup-${fileStamp()}.db")
            call.respondFile(file.toFile())
        } finally {
            file.deleteIfExists()
        }
    }
}

private fun ApplicationCall.downloadAs(filename: String) {
    val disposition = ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename)
    response.header(HttpHeaders.ContentDisposition, disposition.toString())
}

private fun StringBuilder.appendCsvRow(vararg values: String) {
    values.joinTo(this, ",") { value ->
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
    append("\r\n")
}

private fun localTime(instant: Instant): String =
    LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).withNano(0).toString().replace('T', ' ')

private fun fileStamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
