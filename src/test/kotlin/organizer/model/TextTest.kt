package organizer.model

import organizer.web.parseTags
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {
    @Test
    fun `natural order sorts numbers by value and ignores case`() {
        val sorted = listOf("Drawer 10", "drawer 11", "Drawer 2", "Drawer 1", "Bin").sortedWith(NaturalOrder)
        assertEquals(listOf("Bin", "Drawer 1", "Drawer 2", "Drawer 10", "drawer 11"), sorted)
    }

    @Test
    fun `name ranges expand numbers, letters and combinations`() {
        assertEquals(listOf("Drawer 1", "Drawer 2", "Drawer 3"), NameRanges.expand("Drawer {1-3}"))
        assertEquals(listOf("A1", "A2", "B1", "B2"), NameRanges.expand("{A-B}{1-2}"))
        assertEquals(listOf("Bin 08", "Bin 09", "Bin 10"), NameRanges.expand("Bin {08-10}"))
        assertEquals(listOf("Shelf 3", "Shelf 2", "Shelf 1"), NameRanges.expand("Shelf {3-1}"))
        assertEquals(listOf("Box {big}"), NameRanges.expand("Box {big}"))
        assertEquals(9_999L * 9_999L, NameRanges.count("{1-9999}{1-9999}"))
    }

    @Test
    fun `search normalization folds case, accents and look-alikes`() {
        assertEquals("direnc", normalizeForSearch("DİRENÇ"))
        assertEquals("isik", normalizeForSearch("Işık"))
        assertEquals(normalizeForSearch("10 µF"), normalizeForSearch("10 μF"))
        assertEquals(listOf("10k", "0805"), searchTerms("  10K   0805 10k "))
    }

    @Test
    fun `tags are split on commas and deduplicated`() {
        assertEquals(listOf("resistor", "SMD"), parseTags("resistor, #SMD, smd,, "))
    }
}
