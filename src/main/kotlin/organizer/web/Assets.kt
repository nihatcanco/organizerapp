package organizer.web

import kotlinx.html.Tag
import java.util.zip.CRC32

/** Static file URLs with a content hash, so browsers re-download them only after they change. */
object Assets {
    private val version: String by lazy {
        val crc = CRC32()
        for (name in listOf("app.css", "app.js", "icon.svg")) {
            Assets::class.java.getResourceAsStream("/static/$name")?.use { crc.update(it.readBytes()) }
        }
        crc.value.toString(36)
    }

    fun url(name: String) = "/static/$name?v=$version"
}

// 24×24 line icons, styled by the .icon class (stroke = currentColor).
private val ICONS = mapOf(
    "cabinet" to """<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M4 9h16M4 15h16M10 6h4M10 12h4M10 18h4"/>""",
    "drawer" to """<path d="M3 9.5 5 5h14l2 4.5"/><rect x="3" y="9.5" width="18" height="9.5" rx="1.5"/><path d="M9.5 14h5"/>""",
    "folder" to """<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>""",
    "folder-plus" to """<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M12 10v6M9 13h6"/>""",
    "box" to """<path d="m3 7.5 9-4.5 9 4.5v9L12 21l-9-4.5z"/><path d="m3 7.5 9 4.5 9-4.5M12 12v9"/>""",
    "search" to """<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>""",
    "plus" to """<path d="M12 5v14M5 12h14"/>""",
    "minus" to """<path d="M5 12h14"/>""",
    "edit" to """<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/>""",
    "trash" to """<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>""",
    "copy" to """<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3"/>""",
    "qr" to """<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h2v2h-2zM18 18h2v2h-2zM14 18v2M18 14h2"/>""",
    "tag" to """<path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z"/><circle cx="8" cy="8" r="1.5"/>""",
    "chevron" to """<path d="m9 6 6 6-6 6"/>""",
    "camera" to """<path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/>""",
    "printer" to """<path d="M7 9V3h10v6"/><rect x="3" y="9" width="18" height="8" rx="2"/><path d="M7 14h10v7H7z"/>""",
    "download" to """<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>""",
    "x" to """<path d="M6 6l12 12M18 6 6 18"/>""",
    "alert" to """<path d="M10.3 4.2 2.6 17.5A2 2 0 0 0 4.3 20.5h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z"/><path d="M12 9.5v4M12 17h.01"/>""",
    "check" to """<path d="m5 12.5 4.5 4.5L19 7.5"/>""",
)

fun Tag.icon(name: String) {
    val paths = ICONS.getValue(name)
    consumer.onTagContentUnsafe { +"""<svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">$paths</svg>""" }
}
