package organizer.ai

import organizer.model.MAX_QUANTITY
import organizer.model.Property
import organizer.model.normalizeForSearch

/** What a photo suggests for the item form; anything that wasn't found stays null or empty. */
data class ItemSuggestion(
    val name: String?,
    val quantity: Int?,
    val tags: List<String>,
    val properties: List<Property>,
    val notes: String?,
    /** Everything the model read, line by line, shown to the user for reference. */
    val text: List<String>,
    val caption: String,
)

/**
 * Turns the text read from a photo (bag labels from LCSC, Digi-Key, Mouser…, or markings on a part)
 * and the model's one-line description into item details, using rules for electronic components.
 */
fun suggestItem(lines: List<String>, caption: String): ItemSuggestion {
    val fields = linkedMapOf<Field, String>()
    val freeText = mutableListOf<String>()
    for (line in lines) {
        val found = labelFields(line)
        if (found.isEmpty()) freeText += line
        for ((field, value) in found) if (value.isNotEmpty()) fields.putIfAbsent(field, value)
    }
    fields[Field.LcscPart]?.let { fields[Field.LcscPart] = lcscCode(it) }
    val description = fields[Field.Description]
    // Values and packages come from the description first, then any loose text; never from part numbers.
    val specText = listOfNotNull(description, fields[Field.Package]) + freeText
    val specs = Spec.entries.mapNotNull { spec -> specText.firstNotNullOfOrNull(spec::find)?.let { spec to it } }.toMap(LinkedHashMap())

    // Only the caption's subject counts: "a board with a usb cable" is a board, not a cable.
    val subject = nameFromCaption(caption)
    val categories = categories((listOfNotNull(description, subject) + freeText).joinToString("\n"), specs)
    if (Spec.Resistance !in specs && categories.firstOrNull() == "resistor") {
        specText.firstNotNullOfOrNull(::bareResistance)?.let { specs[Spec.Resistance] = it }
    }
    val partNumber = fields[Field.PartNumber] ?: freeText.firstNotNullOfOrNull(::partNumberLike)

    val name = when {
        categories.firstOrNull() == "resistor" && Spec.Resistance in specs -> "Resistor ${specs[Spec.Resistance]}"
        categories.firstOrNull() == "capacitor" && Spec.Capacitance in specs -> "Capacitor ${specs[Spec.Capacitance]}"
        categories.firstOrNull() == "inductor" && Spec.Inductance in specs -> "Inductor ${specs[Spec.Inductance]}"
        categories.firstOrNull() == "crystal" && Spec.Frequency in specs -> "Crystal ${specs[Spec.Frequency]}"
        partNumber != null -> partNumber
        description != null -> shorten(description)
        else -> subject
    }

    val properties = buildList {
        partNumber?.let { add(Property("Part number", it)) }
        fields[Field.Manufacturer]?.let { add(Property("Manufacturer", it)) }
        for (spec in Spec.entries) specs[spec]?.let { add(Property(spec.label, it)) }
        for (field in listOf(Field.LcscPart, Field.DigikeyPart, Field.MouserPart)) {
            fields[field]?.let { add(Property(field.label!!, it)) }
        }
    }
    val mounting = specs[Spec.Package]?.let(::mounting) ?: mountingFromText((listOfNotNull(description) + freeText).joinToString(" "))

    return ItemSuggestion(
        name = name,
        quantity = fields[Field.Quantity]?.let(::firstNumber) ?: freeText.firstNotNullOfOrNull(::piecesCount),
        tags = (categories.take(3) + listOfNotNull(mounting)).distinct(),
        properties = properties.distinctBy { it.name },
        notes = description?.takeIf { it != name },
        text = lines,
        caption = caption,
    )
}

// ---------- "Key: value" fields on labels ----------

private enum class Field(val label: String?, keys: String) {
    LcscPart("LCSC part", """LCSC\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?"""),
    DigikeyPart("Digi-Key part", """DIGI-?\s?KEY\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?"""),
    MouserPart("Mouser part", """MOUSER\s*(?:PART)?\s*(?:NO\.?|NUMBER|#)?"""),
    PartNumber(null, """(?:MFR\.?|MFG\.?|MANUFACTURER(?:'?S)?)\s*(?:PART\s*(?:NO\.?|NUMBER|#)?|P/?N|#)|MPN|PART\s*(?:NO\.?|NUMBER|#)|P/N"""),
    Manufacturer(null, """MANUFACTURER|MFR\.?|MFG\.?|BRAND"""),
    Description(null, """DESCRIPTION|DESC\.?"""),
    Quantity(null, """QUANTITY|QTY\.?|Q'TY"""),
    Package(null, """PACKAGE|PKG\.?"""),
    Ignored(null, """CUSTOMER(?:\s*(?:NO\.?|#|PO|REF))?|CUST\.?(?:\s*(?:REF|PO))?|SALES\s*ORDER|ORDER(?:\s*(?:NO\.?|#))?|INVOICE|LOT(?:\s*(?:NO\.?|#))?|DATE\s*CODE|D/C|COO|COUNTRY\s*OF\s*ORIGIN|WEIGHT""");

    val key = Regex("""(?<![\w-])(?:$keys)(?!\w)\s*[:#]?""", RegexOption.IGNORE_CASE)
}

/**
 * Finds "Key: value" pairs in a line. A line may hold several ("LCSC#: C25804 Qty: 100"),
 * so each value runs until the next key.
 */
private fun labelFields(line: String): List<Pair<Field, String>> {
    val keys = Field.entries
        .flatMap { field -> field.key.findAll(line).map { field to it.range } }
        .sortedWith(compareBy<Pair<Field, IntRange>> { it.second.first }.thenByDescending { it.second.last })
    val accepted = mutableListOf<Pair<Field, IntRange>>()
    for (key in keys) if (accepted.none { it.second.last >= key.second.first }) accepted += key
    return accepted.mapIndexed { index, (field, range) ->
        val end = accepted.getOrNull(index + 1)?.second?.first ?: line.length
        val value = line.substring(range.last + 1, end).trim().trim(':', '#', '-', '.', ' ')
        field to value.replace(GLUED_KEY_END, "")
    }.filter { it.first != Field.Ignored }
}

// OCR sometimes runs a key into its value ("PART NUMBER296-1395-5-ND"); the key then matches only up
// to "PART" and "NUMBER" is left at the start of the value.
private val GLUED_KEY_END = Regex("""^(?:PART\s*)?NUMBER\s*[:#]?\s*""", RegexOption.IGNORE_CASE)

// ---------- Component values ----------

private const val NUMBER = """(\d+(?:[.,]\d+)?)"""

private enum class Spec(val label: String, private val pattern: Regex, private val format: (MatchResult) -> String?) {
    // Ω may arrive as the Greek capital omega (U+03A9) or the ohm sign (U+2126).
    Resistance("Resistance", Regex("""(?<![\w.])$NUMBER\s*([kKMG]?)\s*(?:Ω|Ω|[oO][hH][mM][sS]?)(?!\w)"""), { m ->
        "${number(m)} ${prefix(m.groupValues[2])}Ω"
    }),
    Capacitance("Capacitance", Regex("""(?<![\w.])$NUMBER\s*([pnuµμmPNU])[fF](?!\w)"""), { m ->
        "${number(m)} ${prefix(m.groupValues[2])}F"
    }),
    Inductance("Inductance", Regex("""(?<![\w.])$NUMBER\s*([nuµμmNU])[hH](?!\w)"""), { m ->
        "${number(m)} ${prefix(m.groupValues[2])}H"
    }),
    Frequency("Frequency", Regex("""(?<![\w.])$NUMBER\s*([kKMG]?)Hz(?!\w)""", RegexOption.IGNORE_CASE), { m ->
        "${number(m)} ${prefix(m.groupValues[2]).replace("m", "M")}Hz"
    }),
    Tolerance("Tolerance", Regex("""(?:±\s*|(?<![\w.]))$NUMBER\s*%(?!\w)"""), { m ->
        number(m).takeIf { (it.toDoubleOrNull() ?: 100.0) <= 20 }?.let { "±$it%" }
    }),
    Voltage("Voltage", Regex("""(?<![\w.])$NUMBER\s*(k?)V(?:DC|AC)?(?!\w)"""), { m -> "${number(m)} ${m.groupValues[2]}V" }),
    Current("Current", Regex("""(?<![\w.])$NUMBER\s*([mµu]?)A(?!\w)"""), { m -> "${number(m)} ${prefix(m.groupValues[2])}A" }),
    Power("Power", Regex("""(?<![\w./])(\d+(?:[.,]\d+)?(?:/\d+)?)\s*(m?)W(?!\w)"""), { m -> "${number(m)} ${m.groupValues[2]}W" }),
    Package("Package", Regex(
        """(?<![\w.-])(01005|0201|0402|0603|0805|1206|1210|1812|2010|2512|(?:SOT|SOD|SOIC|SOP|SSOP|TSSOP|MSOP|ESOP|QFN|DFN|LQFP|TQFP|QFP|PDIP|DIP|TO|DO)-?\d+[A-Z]*(?:-\d+)?|SMA|SMB|SMC|D2?PAK|MELF|BGA-?\d*)(?![\w.-])""",
        RegexOption.IGNORE_CASE,
    ), { m ->
        m.groupValues[1].uppercase().replace(PACKAGE_FAMILY, "$1-$2")
    });

    fun find(text: String): String? = pattern.findAll(text).firstNotNullOfOrNull(format)
}

// "SOT23" → "SOT-23", the usual spelling.
private val PACKAGE_FAMILY = Regex("""^(SOT|SOD|SOIC|SOP|SSOP|TSSOP|MSOP|ESOP|QFN|DFN|LQFP|TQFP|QFP|PDIP|DIP|TO|DO|BGA)(\d)""")

private fun number(match: MatchResult) = match.groupValues[1].replace(',', '.')

private fun prefix(symbol: String) = when (symbol) {
    "K" -> "k"
    "u", "U", "μ" -> "µ"
    "N" -> "n"
    "P" -> "p"
    else -> symbol
}

/**
 * "10K" or "4.7k" next to the word resistor, as cheap bags of resistors are often labelled.
 * Also takes "10KQ" and "10KO": OCR often reads Ω as Q or O.
 */
private fun bareResistance(text: String): String? =
    Regex("""(?<![\w.])$NUMBER\s*([kKM])[QO]?(?![\w.])""").find(text)?.let { "${number(it)} ${prefix(it.groupValues[2])}Ω" }

/** LCSC codes are C plus digits, so letters read in their place are look-alike digits: C2580A → C25804. */
private fun lcscCode(text: String): String {
    val code = text.substringBefore(' ').uppercase()
    if (!code.startsWith("C") || code.length < 4) return text
    val digits = code.drop(1).map { LOOK_ALIKE_DIGITS[it] ?: it }.joinToString("")
    return if (digits.all(Char::isDigit)) "C$digits" else text
}

private val LOOK_ALIKE_DIGITS = mapOf('O' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'L' to '1', 'Z' to '2', 'A' to '4', 'S' to '5', 'G' to '6', 'T' to '7', 'B' to '8')

// ---------- What kind of part it is ----------

private val CATEGORIES: List<Pair<String, List<String>>> = listOf(
    "resistor" to listOf("resistor", "resistors", "res", "ohm", "ohms"),
    "capacitor" to listOf("capacitor", "capacitors", "cap", "mlcc", "electrolytic", "tantalum"),
    "inductor" to listOf("inductor", "inductors", "choke"),
    "crystal" to listOf("crystal", "oscillator", "resonator"),
    "led" to listOf("led", "leds"),
    "diode" to listOf("diode", "diodes", "rectifier", "schottky", "zener"),
    "transistor" to listOf("transistor", "transistors", "mosfet", "bjt", "npn", "pnp"),
    "regulator" to listOf("regulator", "regulators", "reg", "ldo"),
    "fuse" to listOf("fuse", "fuses"),
    "relay" to listOf("relay", "relays"),
    "switch" to listOf("switch", "switches", "button", "buttons", "tactile"),
    "connector" to listOf("connector", "connectors", "header", "headers", "terminal", "terminals", "jst"),
    "sensor" to listOf("sensor", "sensors"),
    // A trailing * also matches longer part names: "atmega*" finds ATMEGA328P.
    "microcontroller" to listOf("microcontroller", "mcu", "arduino", "esp32*", "esp8266*", "stm32*", "atmega*", "attiny*", "rp2040*"),
    "module" to listOf("module", "modules", "breakout"),
    "ic" to listOf("ic", "ics", "amplifier", "op-amp", "opamp", "timer", "driver", "eeprom"),
    "cable" to listOf("cable", "cables", "wire", "wires"),
    "battery" to listOf("battery", "batteries", "18650", "cr2032"),
    "hardware" to listOf("screw", "screws", "nut", "nuts", "bolt", "bolts", "standoff", "standoffs", "spacer", "spacers"),
)

private fun categories(text: String, specs: Map<Spec, String>): List<String> {
    val normalized = normalizeForSearch(text)
    val found = CATEGORIES.filter { (_, words) ->
        words.any { word ->
            val end = if (word.endsWith('*')) "" else "(?![a-z0-9])"
            Regex("""(?<![a-z0-9])${Regex.escape(word.removeSuffix("*"))}$end""").containsMatchIn(normalized)
        }
    }.map { it.first }.toMutableList()
    // A value with its unit gives the kind away even when the label never says it.
    val implied = listOf(Spec.Resistance to "resistor", Spec.Capacitance to "capacitor", Spec.Inductance to "inductor")
    for ((spec, tag) in implied) if (spec in specs && tag !in found) found += tag
    return found.sortedBy { tag -> CATEGORIES.indexOfFirst { it.first == tag } }
}

private val SURFACE_MOUNT = Regex("""^(?:01005|0201|0402|0603|0805|1206|1210|1812|2010|2512|SOT|SOD|SOIC|SOP|SSOP|TSSOP|MSOP|ESOP|QFN|DFN|LQFP|TQFP|QFP|SMA|SMB|SMC|D2?PAK|MELF|BGA|TO-252|TO-263)""")
private val THROUGH_HOLE = Regex("""^(?:DIP|PDIP|TO-92|TO-220|TO-247|TO-3|DO-41|DO-35|DO-15|DO-201)""")

private fun mounting(packageName: String): String? = when {
    SURFACE_MOUNT.containsMatchIn(packageName) -> "smd"
    THROUGH_HOLE.containsMatchIn(packageName) -> "through-hole"
    else -> null
}

private fun mountingFromText(text: String): String? {
    val normalized = normalizeForSearch(text)
    return when {
        Regex("""\bsmd\b|surface mount""").containsMatchIn(normalized) -> "smd"
        Regex("""\bthrough[ -]hole\b|\btht\b""").containsMatchIn(normalized) -> "through-hole"
        else -> null
    }
}

// ---------- Names and numbers ----------

/**
 * A token that looks like a part number, such as LM317T, ESP32-WROOM-32 or AMS1117-3.3. Ones with several
 * letters win over ones like 1N4148, which look more like lot or date codes.
 */
private fun partNumberLike(text: String): String? {
    val candidates = Regex("""(?<![\w-])([A-Z0-9]+(?:[-.][A-Z0-9]+)*)(?![\w-])""").findAll(text)
        .map { it.groupValues[1] }
        .filter { token ->
            token.length >= 4 && token.any(Char::isDigit) && token.any(Char::isLetter) &&
                Spec.entries.none { it.find(token) != null } && piecesCount(token) == null && bareResistance(token) == null
        }
        .toList()
    return candidates.firstOrNull { token -> token.count(Char::isLetter) >= 2 } ?: candidates.firstOrNull { it.length >= 5 }
}

private fun firstNumber(text: String): Int? =
    Regex("""\d[\d,.]*""").find(text)?.value?.filter(Char::isDigit)?.toIntOrNull()?.takeIf { it in 1..MAX_QUANTITY }

/** "100PCS", "50 pcs", "25 pieces". */
private fun piecesCount(text: String): Int? =
    Regex("""(?<![\w.])(\d[\d,]*)\s*(?:pcs|pc|pieces|pce)(?!\w)""", RegexOption.IGNORE_CASE).find(text)
        ?.groupValues?.get(1)?.let(::firstNumber)

private fun shorten(text: String, limit: Int = 80): String =
    if (text.length <= limit) text else text.take(limit).substringBeforeLast(' ').trimEnd(',', ';', '-', ' ') + "…"

/** "A close up of a blue circuit board with a usb cable." → "Blue circuit board". */
private fun nameFromCaption(caption: String): String? {
    val text = caption.trim().trimEnd('.')
        .replace(Regex("""^(?:an?|the)\s+(?:close[- ]?up|picture|photo|photograph|image)\s+of\s+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^(?:an?|the)\s+""", RegexOption.IGNORE_CASE), "")
        .split(Regex("""\s+(?:on|in|with|sitting|lying|next to|that|against|inside)\s+""", RegexOption.IGNORE_CASE))
        .first()
        .trim()
    return text.takeIf { it.length >= 3 }?.replaceFirstChar { it.uppercase() }
}
