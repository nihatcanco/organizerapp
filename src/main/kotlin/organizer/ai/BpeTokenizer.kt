package organizer.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The byte-level BPE tokenizer of BART-family models such as Florence-2 (the same scheme as GPT-2),
 * loaded from a Hugging Face `tokenizer.json`. Added tokens (`<s>`, `<loc_42>`, …) are kept whole.
 */
class BpeTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
    private val addedTokens: Map<Int, String>,
) {
    private val ranks: Map<Pair<String, String>, Int> = merges.withIndex().associate { (rank, pair) -> pair to rank }
    private val tokens: Array<String?> =
        arrayOfNulls<String>(maxOf(vocab.values.max(), addedTokens.keys.maxOrNull() ?: 0) + 1).also { table ->
            vocab.forEach { (token, id) -> table[id] = token }
            addedTokens.forEach { (id, token) -> table[id] = token }
        }

    /** Token ids for [text], wrapped in `<s>` … `</s>` as the model expects. */
    fun encode(text: String): LongArray {
        val ids = mutableListOf(BOS.toLong())
        for (word in PRE_TOKENIZE.findAll(text)) {
            val symbols = word.value.toByteArray(Charsets.UTF_8).map { BYTE_TO_CHAR[it.toInt() and 0xFF] }.joinToString("")
            for (piece in bpe(symbols)) ids += (vocab[piece] ?: UNK).toLong()
        }
        ids += EOS.toLong()
        return ids.toLongArray()
    }

    /** Text for [ids]; added tokens such as `</s>` or `<loc_12>` appear as written. */
    fun decode(ids: List<Int>): String {
        val text = StringBuilder()
        val bytes = ByteArrayOutputStream()
        fun flush() {
            text.append(bytes.toString(Charsets.UTF_8))
            bytes.reset()
        }
        for (id in ids) {
            val token = tokens.getOrNull(id) ?: continue
            if (id in addedTokens) {
                flush()
                text.append(token)
            } else {
                for (symbol in token) CHAR_TO_BYTE[symbol]?.let(bytes::write)
            }
        }
        flush()
        return text.toString()
    }

    /** Repeatedly joins the adjacent pair that was merged earliest in training. */
    private fun bpe(word: String): List<String> {
        val parts = word.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val rank = ranks[parts[i] to parts[i + 1]] ?: continue
                if (rank < bestRank) {
                    best = i
                    bestRank = rank
                }
            }
            if (best < 0) break
            parts[best] = parts[best] + parts.removeAt(best + 1)
        }
        return parts
    }

    companion object {
        const val BOS = 0
        const val EOS = 2
        const val UNK = 3

        // GPT-2's pre-tokenizer: splits text into words, numbers, punctuation and whitespace runs.
        private val PRE_TOKENIZE = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        // Byte-level BPE works on bytes shown as printable characters: printable bytes map to themselves,
        // the rest to code points from 256 upwards (GPT-2's bytes_to_unicode).
        private val BYTE_TO_CHAR: CharArray = CharArray(256).also { table ->
            val printable = ('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)
            var next = 256
            for (byte in 0 until 256) table[byte] = if (byte in printable) byte.toChar() else (next++).toChar()
        }
        private val CHAR_TO_BYTE: Map<Char, Int> = BYTE_TO_CHAR.withIndex().associate { (byte, char) -> char to byte }

        fun load(file: Path): BpeTokenizer {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val model = root.getValue("model").jsonObject
            val vocab = model.getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int }
            val merges = model.getValue("merges").jsonArray.map { merge ->
                if (merge is JsonArray) {
                    merge[0].jsonPrimitive.content to merge[1].jsonPrimitive.content
                } else {
                    merge.jsonPrimitive.content.split(' ', limit = 2).let { it[0] to it[1] }
                }
            }
            val added = root["added_tokens"]?.jsonArray.orEmpty().associate {
                it.jsonObject.getValue("id").jsonPrimitive.int to it.jsonObject.getValue("content").jsonPrimitive.content
            }
            return BpeTokenizer(vocab, merges, added)
        }
    }
}
