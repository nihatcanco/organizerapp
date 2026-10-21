package organizer.web

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.unsafe
import organizer.model.Location
import organizer.model.LocationTree

private class Label(val title: String, val subtitle: String, val url: String)

private val LABEL_SIZES = linkedMapOf("s" to "Small", "m" to "Medium", "l" to "Large")

fun Route.labelRoutes(app: Services) {
    get("/labels") {
        val tree = app.locations.tree()
        val base = call.publicBaseUrl(app.config)
        val itemId = call.queryParam("item")
        val locationId = call.queryParam("location")
        val labels = when {
            itemId != null -> {
                val item = itemId.toLongOrNull()?.let { app.items.get(it) } ?: throw NotFoundException()
                listOf(Label(item.name, item.locationId?.let(tree::pathName).orEmpty(), "$base/i/${item.id}"))
            }
            locationId != null -> {
                val id = locationId.toLongOrNull()?.takeIf { it in tree } ?: throw NotFoundException()
                tree.subtree(id).map { it.label(tree, base) }
            }
            else -> tree.all().map { it.label(tree, base) }
        }
        val size = call.queryParam("size")?.takeIf { it in LABEL_SIZES } ?: "m"
        val reachable = !isLoopback(Url(base).host)

        call.respondPage("Labels") {
            div("no-print") {
                pageHeader("Print labels") {
                    button(type = ButtonType.button, classes = "btn btn-primary") {
                        attributes["data-print"] = ""
                        icon("printer")
                        +"Print"
                    }
                }
                p {
                    +"Stick these on drawers and boxes. Scanning one with a phone camera opens that page — "
                    +"the phone must be on the same network as this computer."
                }
                if (reachable) {
                    p("muted") { +"Codes link to "; code { +base }; +"." }
                } else {
                    div("notice notice-warn") {
                        icon("alert")
                        span {
                            +"These codes point to "
                            code { +base }
                            +", which phones can't open. Open this page using your computer's network address, "
                            +"or set ORGANIZER_PUBLIC_URL."
                        }
                    }
                }
                div("filters") {
                    span("muted") { +"Size:" }
                    for ((key, label) in LABEL_SIZES) {
                        val href = "/labels?" + Parameters.build {
                            itemId?.let { append("item", it) }
                            locationId?.let { append("location", it) }
                            append("size", key)
                        }.formUrlEncode()
                        a(href, classes = if (key == size) "chip chip-active" else "chip chip-link") { +label }
                    }
                }
                if (labels.size > 1) p("muted") { +"Remove labels you don't need with ×, then print." }
            }
            if (labels.isEmpty()) {
                emptyState("Nothing to label yet", "Create some locations first.")
            } else {
                div("labels labels-$size") { for (label in labels) labelCard(label) }
            }
        }
    }
}

private fun Location.label(tree: LocationTree, base: String) =
    Label(name, parentId?.let(tree::pathName).orEmpty(), "$base/l/$id")

private fun FlowContent.labelCard(label: Label) {
    div("label") {
        div("qr") { unsafe { +qrSvg(label.url) } }
        div("label-text") {
            strong { +label.title }
            if (label.subtitle.isNotEmpty()) span("label-sub") { +label.subtitle }
        }
        button(type = ButtonType.button, classes = "label-remove no-print") {
            attributes["data-remove-label"] = ""
            attributes["aria-label"] = "Remove this label"
            icon("x")
        }
    }
}

/** A QR code as an SVG drawn with a single path, including the standard 4-module quiet zone. */
fun qrSvg(text: String): String {
    val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
    val quiet = 4
    val size = matrix.width + 2 * quiet
    val path = StringBuilder()
    for (y in 0 until matrix.height) {
        var x = 0
        while (x < matrix.width) {
            if (matrix[x, y].toInt() == 1) {
                val start = x
                while (x < matrix.width && matrix[x, y].toInt() == 1) x++
                path.append("M${start + quiet} ${y + quiet}h${x - start}v1h-${x - start}z")
            } else {
                x++
            }
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges" role="img" aria-label="QR code">""" +
        """<rect width="$size" height="$size" fill="#fff"/><path fill="#000" d="$path"/></svg>"""
}
