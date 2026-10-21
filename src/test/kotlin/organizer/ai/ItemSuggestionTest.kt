package organizer.ai

import organizer.model.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemSuggestionTest {
    private fun ItemSuggestion.property(name: String) = properties.firstOrNull { it.name == name }?.value

    @Test
    fun `reads an LCSC bag label`() {
        val suggestion = suggestItem(
            listOf(
                "LCSC Part #: C25804",
                "Mfr.Part #: 0603WAF1002T5E",
                "Manufacturer: UNI-ROYAL(Uniroyal Elec)",
                "Package: 0603",
                "Description: 100mW Thick Film Resistors 75V ±1% ±100ppm/℃ 10kΩ 0603 Chip Resistor - Surface Mount ROHS",
                "Qty: 100",
                "Customer #: 12345",
            ),
            caption = "A white label on a plastic bag.",
        )
        assertEquals("Resistor 10 kΩ", suggestion.name)
        assertEquals(100, suggestion.quantity)
        assertEquals(listOf("resistor", "smd"), suggestion.tags)
        assertEquals(
            listOf(
                Property("Part number", "0603WAF1002T5E"),
                Property("Manufacturer", "UNI-ROYAL(Uniroyal Elec)"),
                Property("Resistance", "10 kΩ"),
                Property("Tolerance", "±1%"),
                Property("Voltage", "75 V"),
                Property("Power", "100 mW"),
                Property("Package", "0603"),
                Property("LCSC part", "C25804"),
            ),
            suggestion.properties,
        )
        assertEquals("100mW Thick Film Resistors 75V ±1% ±100ppm/℃ 10kΩ 0603 Chip Resistor - Surface Mount ROHS", suggestion.notes)
    }

    @Test
    fun `reads a Digi-Key label without colons`() {
        val suggestion = suggestItem(
            listOf(
                "DIGI-KEY PART NUMBER 311-10.0KHRCT-ND",
                "MANUFACTURER PART NUMBER RC0603FR-0710KL",
                "DESCRIPTION RES 10K OHM 1% 1/10W 0603",
                "QUANTITY 100",
                "CUST REF",
            ),
            caption = "",
        )
        assertEquals("Resistor 10 kΩ", suggestion.name)
        assertEquals(100, suggestion.quantity)
        assertEquals("RC0603FR-0710KL", suggestion.property("Part number"))
        assertEquals("311-10.0KHRCT-ND", suggestion.property("Digi-Key part"))
        assertEquals("1/10 W", suggestion.property("Power"))
        assertEquals("±1%", suggestion.property("Tolerance"))
    }

    @Test
    fun `splits two fields printed on one line`() {
        val suggestion = suggestItem(listOf("Mouser #: 603-RC0603FR-0710KL Qty: 50"), caption = "")
        assertEquals("603-RC0603FR-0710KL", suggestion.property("Mouser part"))
        assertEquals(50, suggestion.quantity)
    }

    @Test
    fun `names capacitors by their value`() {
        val suggestion = suggestItem(listOf("DESCRIPTION CAP CER 0.1UF 50V X7R 0603"), caption = "")
        assertEquals("Capacitor 0.1 µF", suggestion.name)
        assertEquals("50 V", suggestion.property("Voltage"))
        assertEquals(listOf("capacitor", "smd"), suggestion.tags)
    }

    @Test
    fun `uses the marking on a chip as its part number`() {
        val suggestion = suggestItem(listOf("ATMEL", "ATMEGA328P", "AU 1824"), caption = "A close up of a black microchip on a table.")
        assertEquals("ATMEGA328P", suggestion.name)
        assertEquals(listOf("microcontroller"), suggestion.tags)
        assertEquals("ATMEGA328P", suggestion.property("Part number"))
    }

    @Test
    fun `understands a hand-labelled bag with help from the caption`() {
        val suggestion = suggestItem(listOf("10K 0805", "100PCS"), caption = "A bag of resistors on a table.")
        assertEquals("Resistor 10 kΩ", suggestion.name)
        assertEquals(100, suggestion.quantity)
        assertEquals(listOf("resistor", "smd"), suggestion.tags)
    }

    @Test
    fun `repairs typical OCR slips`() {
        val suggestion = suggestItem(
            listOf(
                "LCSC Part #: C2580A",
                "DIGI-KEY PART NUMBER296-1395-5-ND",
                "MANUFACTURER PART NUMBERNE555P",
                "Description: 10KQ +1% 100mW thick Film Resistor",
            ),
            caption = "",
        )
        assertEquals("Resistor 10 kΩ", suggestion.name)
        assertEquals("C25804", suggestion.property("LCSC part"))
        assertEquals("296-1395-5-ND", suggestion.property("Digi-Key part"))
        assertEquals("NE555P", suggestion.property("Part number"))
        assertEquals("±1%", suggestion.property("Tolerance"))
    }

    @Test
    fun `falls back to the caption's subject when there is no text`() {
        val suggestion = suggestItem(emptyList(), caption = "A close up of a blue circuit board with a usb cable.")
        assertEquals("Blue circuit board", suggestion.name)
        assertEquals(emptyList(), suggestion.tags)
        assertNull(suggestion.quantity)
        assertEquals(emptyList(), suggestion.properties)
    }
}
