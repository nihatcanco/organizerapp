package organizer.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import organizer.model.MAX_QUANTITY
import organizer.model.Property
import organizer.model.normalizeForSearch

/** What a photo or name suggests for the item form; anything that wasn't found stays null or empty. */
data class ItemSuggestion(
    val name: String?,
    val quantity: Int?,
    val tags: List<String>,
    val properties: List<Property>,
    val notes: String?,
    /** Web pages or image files that show the item, best first, when Claude was asked for them. */
    val photoPages: List<String> = emptyList(),
    /** The photo found at [photoPages]. */
    val photo: WebPhoto? = null,
)

// What Part number means follows the list: written inside it, as "Part number (the maker's)", Claude sometimes
// copied the remark into the property's name.
private const val PROPERTY_LIST = "Brand, Model, Size, Weight, Volume, Color, Material, Part number, Manufacturer, Package, " +
    "Resistance, Capacitance, Voltage, Tolerance, Power, LCSC part, Digi-Key part, Mouser part. Part number is the maker's own"

/**
 * What Claude is asked about an item, from its photo, its typed [name], or both; [suggestItem] reads the answer.
 * Claude may search the web for what the photo or name doesn't say, and for an item without a photo, names
 * pages that show one.
 */
internal fun itemQuestion(name: String?, photo: Boolean): String {
    require(photo || name != null) { "Needs a photo or a name" }
    val quoted = name?.let { JsonPrimitive(it).toString() }
    val kinds = "food, a household product, a tool, a spare part, an electronic component or anything else"
    val task = when {
        photo && quoted != null -> "This photo shows $quoted, something to add to a home inventory: $kinds. Say what it is, reading any label or packaging"
        photo -> "This photo shows something to add to a home inventory: $kinds. Say what it is, reading any label or packaging"
        else -> "Someone is adding $quoted to a home inventory: $kinds. Fill in the rest from what the name says and what you know about this exact item"
    }
    val search = if (photo) {
        "You can search the web to identify the exact item and to check facts that aren't printed on it"
    } else {
        "You can search the web to identify the exact item, to check facts that the name doesn't say and to find a photo of it"
    }
    val nameRule = if (quoted != null) {
        "as given."
    } else {
        "short, as written on a box. Electronic components by kind and value (\"Resistor 10 kΩ\", \"Capacitor 100 nF\") or by part number " +
            "for chips (\"NE555P timer\"); other things by brand, product and size (\"Persil Universal Gel 1.5 L\") or plainly (\"Banana\")."
    }
    val quantityRule = if (photo) {
        "how many pieces the pack holds when it says so (\"Qty: 100 PCS\" is 100), or how many you can count; otherwise null."
    } else {
        "how many pieces when the name says so (\"pack of 100\" is 100); otherwise null."
    }
    val propertyRule = if (photo) {
        "facts printed on it or certain from what it is or that you found for this exact item, under these names where they fit, " +
            "not the label's own: $PROPERTY_LIST. Copy values exactly as printed. Leave out what isn't printed unless it's plain from " +
            "the item itself or you found it for this exact item, and never guess a brand or manufacturer."
    } else {
        "facts the name states or that are always true of this exact item or that you found for it, under these names where they fit: " +
            "$PROPERTY_LIST. When the name could be several different products, give only what they all share; never guess a value you aren't sure of."
    }
    // An item with a photo has one already. Addresses must come from the search: remembered ones are mostly wrong.
    val photosField = if (photo) "" else """, "photos": []"""
    val photosRule = if (photo) {
        ""
    } else {
        "\n- photos: up to three addresses from your search of pages that show this exact item, best first, such as the maker's product page " +
            "or a shop's; an image file's own address works too. Prefer ones whose photo shows the item alone on a white or transparent " +
            "background. [] when no page is surely about this exact item."
    }
    return """
        |$task, and answer with only a JSON object on one line. $search; use what you find only when it is surely about this exact item.
        |{"name": "…", "quantity": null, "tags": [], "properties": {}, "notes": null$photosField}
        |
        |- name: $nameRule
        |- quantity: $quantityRule
        |- tags: one to three lowercase categories, such as ["food", "fruit"], ["cleaning", "detergent"] or ["resistor", "smd"].
        |- properties: $propertyRule
        |- notes: one short sentence on what it is or what it's for, or null.$photosRule
    """.trimMargin()
}

private val LENIENT = Json { isLenient = true }
private val NOTHING = setOf("", "null", "none", "n/a", "unknown", "-")
private val FIELDS = setOf("name", "quantity", "tags", "properties", "notes", "photos")

// Models sometimes copy a label's own key ("Mfr.Part #") instead of the name it was asked to use, or add
// a remark in brackets ("Part number (the maker's)").
private val PROPERTY_NAMES: List<Pair<Regex, String>> = listOf(
    """LCSC\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?""" to "LCSC part",
    """DIGI-?\s?KEY\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?""" to "Digi-Key part",
    """MOUSER\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?""" to "Mouser part",
    """(?:MFR\.?|MFG\.?|MANUFACTURER(?:'?S)?)\s*(?:PART\s*(?:NO\.?|NUMBER|#)?|P/?N|#)|MPN|PART\s*(?:NO\.?|NUMBER|#)|P/N""" to "Part number",
    """MFR\.?|MFG\.?""" to "Manufacturer",
    """PKG\.?""" to "Package",
).map { (keys, name) -> Regex("""(?:$keys)\s*(?:\([^)]*\))?\s*:?""", RegexOption.IGNORE_CASE) to name }

private fun propertyName(name: String): String = PROPERTY_NAMES.firstOrNull { it.first.matches(name) }?.second ?: name

/**
 * Reads Claude's answer to [itemQuestion]: a JSON object, possibly in a ```json block or with words
 * around it. Values that are missing, empty or don't fit the form are left out.
 */
fun suggestItem(answer: String): ItemSuggestion {
    val json = answer.substringAfter('{', "").substringBeforeLast('}', "")
    val root = runCatching { LENIENT.parseToJsonElement("{$json}").jsonObject }.getOrElse { JsonObject(emptyMap()) }
    val name = root["name"].text()?.take(200)
    return ItemSuggestion(
        name = name,
        quantity = root["quantity"].text()?.let { Regex("""\d+""").find(it.replace(",", ""))?.value?.toIntOrNull() }?.takeIf { it in 1..MAX_QUANTITY },
        tags = root["tags"].items().mapNotNull { it.text()?.lowercase()?.replace(',', ' ')?.removePrefix("#")?.trim()?.take(50) }
            .filter { it.isNotEmpty() }.distinctBy(::normalizeForSearch).take(4),
        properties = properties(root["properties"]),
        notes = root["notes"].text()?.takeIf { it != name },
        photoPages = root["photos"].items().mapNotNull { it.text() }
            .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct().take(3),
    )
}

/** Properties as {"Brand": "Persil"}, or as [{"name": "Brand", "value": "Persil"}] in case the model writes them so. */
private fun properties(element: JsonElement?): List<Property> {
    val pairs = when (element) {
        is JsonObject -> element.map { (name, value) -> name to value.text() }
        is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let { property -> property["name"].text() to property["value"].text() } }
        else -> emptyList()
    }
    return pairs.mapNotNull { (name, value) ->
        val propertyName = name?.trim()?.let(::propertyName)?.take(100)
        // Models sometimes repeat a field, such as the notes, among the properties.
        if (propertyName.isNullOrEmpty() || value == null || propertyName.lowercase() in FIELDS) null else Property(propertyName, value.take(1000))
    }.distinctBy { normalizeForSearch(it.name) }.take(20)
}

/** A string or number as trimmed text, or null when it's empty or a stand-in such as "unknown". */
private fun JsonElement?.text(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeUnless { it.lowercase() in NOTHING }

private fun JsonElement?.items(): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonPrimitive -> listOf(this) // a lone tag instead of a list
    else -> emptyList()
}
