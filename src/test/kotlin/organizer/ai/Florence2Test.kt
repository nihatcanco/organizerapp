package organizer.ai

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class Florence2Test {
    @Test
    fun `turns the model's OCR answer into lines, joining pieces at the same height`() {
        fun box(x: Int, y: Int, w: Int, h: Int) = listOf(x, y, x + w, y, x + w, y + h, x, y + h).joinToString("") { "<loc_$it>" }
        val answer = "</s><s>Qty:${box(100, 300, 80, 40)}LCSC#: C25804${box(100, 100, 400, 40)}100${box(400, 302, 60, 38)}</s>"
        assertEquals(listOf("LCSC#: C25804", "Qty: 100"), Florence2.textLines(answer))
    }

    /** Runs the real model, so only when one is given: ./gradlew test -Dorganizer.test.models=path/to/florence-2-base-ft */
    @Test
    fun `reads a component label photo`() {
        val dir = testModelDirectory()
        assumeTrue(dir != null, "no model directory given")
        Florence2(dir!!).use { model ->
            val reading = model.read(labelImage(*SAMPLE_LABEL))
            val suggestion = suggestItem(reading.lines, reading.caption)
            assertContains(reading.lines.joinToString("\n"), "0603WAF1002T5E")
            assertEquals("Resistor 10 kΩ", suggestion.name)
            assertEquals(100, suggestion.quantity)
            assertEquals("C25804", suggestion.properties.first { it.name == "LCSC part" }.value)
            assertEquals("0603", suggestion.properties.first { it.name == "Package" }.value)
        }
    }
}
