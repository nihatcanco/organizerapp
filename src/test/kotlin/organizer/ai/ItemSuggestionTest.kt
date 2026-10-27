package organizer.ai

import organizer.model.Property
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ItemSuggestionTest {
    @Test
    fun `reads the model's answer`() {
        val suggestion = suggestItem(
            """
            {"name": "Resistor 10 kΩ", "quantity": 100, "tags": ["resistor", "smd"],
             "properties": {"Part number": "0603WAF1002T5E", "Package": "0603", "LCSC part": "C25804"},
             "notes": "10 kΩ ±1% 100 mW thick film chip resistor."}
            """.trimIndent(),
        )
        assertEquals(
            ItemSuggestion(
                name = "Resistor 10 kΩ",
                quantity = 100,
                tags = listOf("resistor", "smd"),
                properties = listOf(Property("Part number", "0603WAF1002T5E"), Property("Package", "0603"), Property("LCSC part", "C25804")),
                notes = "10 kΩ ±1% 100 mW thick film chip resistor.",
            ),
            suggestion,
        )
    }

    @Test
    fun `finds the answer in a code block with words around it`() {
        val suggestion = suggestItem("Here you go:\n```json\n{\"name\": \"Banana\", \"tags\": [\"food\", \"fruit\"]}\n```\nEnjoy!")
        assertEquals("Banana", suggestion.name)
        assertEquals(listOf("food", "fruit"), suggestion.tags)
    }

    @Test
    fun `leaves out stand-ins and values the form can't take`() {
        val suggestion = suggestItem(
            """
            {"name": "Detergent", "quantity": "unknown", "tags": ["Cleaning", "cleaning", "#Laundry", ""],
             "properties": {"Brand": "N/A", "Volume": "1.5 L", "Scent": null, "Weight": 1.6, "notes": "1.5 L"},
             "notes": "Detergent"}
            """.trimIndent(),
        )
        assertEquals(null, suggestion.quantity)
        assertEquals(listOf("cleaning", "laundry"), suggestion.tags)
        assertEquals(listOf(Property("Volume", "1.5 L"), Property("Weight", "1.6")), suggestion.properties)
        assertEquals(null, suggestion.notes) // the same as the name
        assertEquals(null, suggestItem("""{"quantity": 0}""").quantity)
        assertEquals(100, suggestItem("""{"quantity": "100 PCS"}""").quantity)
    }

    @Test
    fun `uses the app's names for keys copied from a label`() {
        val suggestion = suggestItem(
            """{"properties": {"LCSC Part #": "C25804", "Mfr.Part #": "0603WAF1002T5E", "Digi-Key Part Number:": "311-10.0KHRCT-ND", "MPN": "X", "Mfr.": "UNI-ROYAL", "Pkg": "0603"}}""",
        )
        assertEquals(
            listOf(
                Property("LCSC part", "C25804"),
                Property("Part number", "0603WAF1002T5E"), // the first of two part numbers wins
                Property("Digi-Key part", "311-10.0KHRCT-ND"),
                Property("Manufacturer", "UNI-ROYAL"),
                Property("Package", "0603"),
            ),
            suggestion.properties,
        )
    }

    @Test
    fun `drops remarks in brackets from property names`() {
        val suggestion = suggestItem("""{"properties": {"Part number (the maker's)": "TPS63020DSJR", "LCSC part (C-number)": "C15483"}}""")
        assertEquals(listOf(Property("Part number", "TPS63020DSJR"), Property("LCSC part", "C15483")), suggestion.properties)
    }

    @Test
    fun `accepts properties written as a list`() {
        val suggestion = suggestItem("""{"properties": [{"name": "Package", "value": "0603"}, {"name": "Color"}]}""")
        assertEquals(listOf(Property("Package", "0603")), suggestion.properties)
    }

    @Test
    fun `an answer without JSON suggests nothing`() {
        assertEquals(ItemSuggestion(null, null, emptyList(), emptyList(), null), suggestItem("I can't tell what this is."))
    }

    @Test
    fun `asks about the photo, the typed name, or both`() {
        val photo = itemQuestion(name = null, photo = true)
        assertContains(photo, "This photo shows something to add")
        assertContains(photo, "- name: short, as written on a box.")
        assertContains(photo, "Copy values exactly as printed.")
        assertContains(photo, "You can search the web to identify the exact item and to check facts that aren't printed on it")
        assertFalse("photos" in photo)

        val name = itemQuestion(name = "NE555P", photo = false)
        assertContains(name, "Someone is adding \"NE555P\" to a home inventory")
        assertContains(name, "- name: as given.")
        assertContains(name, "never guess a value you aren't sure of")
        assertContains(name, "to check facts that the name doesn't say and to find pages with a photo of it")
        assertContains(name, "\"photos\": []")
        assertContains(name, "- photos: a list of up to five addresses, copied exactly from your search results")
        assertContains(name, "If your searches found no such page, search for one.")

        val both = itemQuestion(name = "Resistor 10k", photo = true)
        assertContains(both, "This photo shows \"Resistor 10k\", something to add")
        assertContains(both, "reading any label")
        assertFalse("photos" in both)
    }

    @Test
    fun `a typed name can't break out of its quotes`() {
        assertContains(itemQuestion(name = "12\" ruler", photo = false), "adding \"12\\\" ruler\" to")
    }

    @Test
    fun `reads the photo pages, keeping only web addresses`() {
        val suggestion = suggestItem(
            """{"name": "NE555P", "photos": ["https://www.ti.com/product/NE555", "file:///etc/passwd", "javascript:alert(1)", "https://www.ti.com/product/NE555", "http://a.example/1.jpg", "https://b.example/", "https://c.example/", "https://d.example/", "https://e.example/"]}""",
        )
        assertEquals(
            listOf("https://www.ti.com/product/NE555", "http://a.example/1.jpg", "https://b.example/", "https://c.example/", "https://d.example/"),
            suggestion.photoPages,
        )
        assertEquals(emptyList(), suggestItem("""{"name": "NE555P", "photos": null}""").photoPages)
        assertEquals(
            listOf("https://reverb.com/item/123-yamaha-aes420", "https://www.thomann.de/aes420.htm", "https://usa.yamaha.com/aes/"),
            suggestItem(
                """{"photos": [{"url": "https://reverb.com/item/123-yamaha-aes420", "site": "Reverb"}, "www.thomann.de/aes420.htm", {"address": "usa.yamaha.com/aes/"}, "Yamaha's site", {"site": "eBay"}]}""",
            ).photoPages,
        )
        assertEquals(emptyList(), suggestItem("""{"properties": {"photos": "https://a.example/"}}""").properties)
    }
}
