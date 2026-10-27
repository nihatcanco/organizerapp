package organizer.web

import kotlinx.html.FlowContent
import kotlinx.html.a
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun plural(count: Int, word: String, pluralWord: String = word + "s") = "$count ${if (count == 1) word else pluralWord}"

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun formatDate(instant: Instant): String = DATE.format(instant.atZone(ZoneId.systemDefault()))

fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
    val seconds = Duration.between(instant, now).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> plural((seconds / 3600).toInt(), "hour") + " ago"
        seconds < 7 * 86_400 -> plural((seconds / 86_400).toInt(), "day") + " ago"
        else -> "on " + formatDate(instant)
    }
}

/** US dollars, to a hundredth of a cent below a dollar: a single question costs a fraction of a cent. */
fun formatDollars(dollars: Double): String = "$" + String.format(Locale.ROOT, if (dollars < 1) "%.4f" else "%.2f", dollars)

private val URL = Regex("""https?://[^\s<>"']+[^\s<>"'.,;:!?)\]}]""")

/** Text with any http(s) links made clickable, e.g. datasheet URLs in notes. */
fun FlowContent.linkified(text: String) {
    var last = 0
    for (match in URL.findAll(text)) {
        +text.substring(last, match.range.first)
        a(match.value) {
            attributes["target"] = "_blank"
            attributes["rel"] = "noopener noreferrer"
            +match.value
        }
        last = match.range.last + 1
    }
    +text.substring(last)
}
