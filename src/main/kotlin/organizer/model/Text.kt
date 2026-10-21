package organizer.model

import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")

/**
 * Folds case, accents and look-alike characters so that "Direnç", "DIRENC" and "direnc" match,
 * as do "µF" and "μF". Search text is stored in this form and queries are compared against it.
 */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(COMBINING_MARKS, "")
        .replace('ı', 'i')

/** Splits a query into normalized words; an item matches when it contains all of them. */
fun searchTerms(query: String): List<String> =
    normalizeForSearch(query).split(WHITESPACE).filter { it.isNotEmpty() }.distinct().take(12)

/** Orders names the way people expect: "Drawer 2" before "Drawer 10", ignoring case and accents. */
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("[0-9]+|[^0-9]+")

    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(normalizeForSearch(a)).map { it.value }.toList()
        val right = chunks.findAll(normalizeForSearch(b)).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isAsciiDigit() && y[0].isAsciiDigit()) compareNumbers(x, y) else x.compareTo(y)
            if (result != 0) return result
        }
        return if (left.size != right.size) left.size.compareTo(right.size) else a.compareTo(b)
    }

    // Compares digit strings of any length without overflowing.
    private fun compareNumbers(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

/**
 * Expands ranges in braces so a whole cabinet can be created in one go:
 * `Drawer {1-3}` gives Drawer 1, Drawer 2, Drawer 3; `{A-B}{1-2}` gives A1, A2, B1, B2;
 * `Bin {01-12}` keeps the zero padding. Text without a valid range is returned unchanged.
 */
object NameRanges {
    private val RANGE = Regex("""\{(\d{1,4})-(\d{1,4})\}|\{([A-Z])-([A-Z])\}|\{([a-z])-([a-z])\}""")

    /** How many names [pattern] expands to, computed without building them. */
    fun count(pattern: String): Long = RANGE.findAll(pattern).fold(1L) { total, match -> total * values(match).size }

    fun expand(pattern: String): List<String> {
        val match = RANGE.find(pattern) ?: return listOf(pattern)
        val head = pattern.substring(0, match.range.first)
        val tails = expand(pattern.substring(match.range.last + 1))
        return values(match).flatMap { value -> tails.map { head + value + it } }
    }

    private fun values(match: MatchResult): List<String> {
        val (from, to, upperFrom, upperTo, lowerFrom, lowerTo) = match.destructured
        return when {
            from.isNotEmpty() -> {
                val padded = (from.length > 1 && from[0] == '0') || (to.length > 1 && to[0] == '0')
                val width = if (padded) maxOf(from.length, to.length) else 0
                progression(from.toInt(), to.toInt()).map { it.toString().padStart(width, '0') }
            }
            upperFrom.isNotEmpty() -> progression(upperFrom[0].code, upperTo[0].code).map { it.toChar().toString() }
            else -> progression(lowerFrom[0].code, lowerTo[0].code).map { it.toChar().toString() }
        }
    }

    private fun progression(from: Int, to: Int): IntProgression = if (from <= to) from..to else from downTo to
}
