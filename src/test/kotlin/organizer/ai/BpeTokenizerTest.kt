package organizer.ai

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BpeTokenizerTest {
    // A tiny vocabulary in the byte-level style: "Ġ" stands for a space, "Î©" are the two UTF-8 bytes of Ω.
    private val tokenizer = BpeTokenizer(
        vocab = mapOf("<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3, "h" to 4, "i" to 5, "Ġ" to 6, "Î" to 7, "©" to 8, "hi" to 9, "Ġhi" to 10),
        merges = listOf("h" to "i", "Ġ" to "hi"),
        addedTokens = mapOf(0 to "<s>", 1 to "<pad>", 2 to "</s>", 3 to "<unk>", 11 to "<loc_5>"),
    )

    @Test
    fun `encodes with merges, byte-level symbols and start and end tokens`() {
        assertContentEquals(longArrayOf(0, 9, 10, 6, 7, 8, 3, 2), tokenizer.encode("hi hi Ωx"))
    }

    @Test
    fun `decodes bytes back to text and keeps added tokens whole`() {
        assertEquals("<s>hi hi Ω<loc_5></s>", tokenizer.decode(listOf(0, 9, 10, 6, 7, 8, 11, 2)))
    }
}
