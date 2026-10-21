package organizer.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max

/**
 * Microsoft's Florence-2 vision model (MIT licensed), run inside the app with ONNX Runtime.
 * It reads the text in a photo and describes what the photo shows.
 */
class Florence2(dir: Path) : AutoCloseable {
    class Reading(val lines: List<String>, val caption: String)

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Leave half the cores for serving pages while a photo is being read.
        setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
    }
    private val visionEncoder = env.createSession(dir.resolve("vision_encoder.onnx").toString(), options)
    private val embedTokens = env.createSession(dir.resolve("embed_tokens_quantized.onnx").toString(), options)
    private val encoder = env.createSession(dir.resolve("encoder_model_quantized.onnx").toString(), options)
    private val decoder = env.createSession(dir.resolve("decoder_model_merged_quantized.onnx").toString(), options)
    private val tokenizer = BpeTokenizer.load(dir.resolve("tokenizer.json"))
    private val noPast = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_SIZE.toLong()))

    fun read(image: BufferedImage): Reading {
        val started = System.nanoTime()
        val features = imageFeatures(image)
        val seen = System.nanoTime()
        // Labels repeat themselves ("0603" in the part number and the package), so text may reuse short
        // sequences; the window only stops runaway loops. Captions keep the model's tuned setting of 3.
        val text = generate(features, "What is the text in the image, with regions?", maxTokens = 700, noRepeat = 10)
        val read = System.nanoTime()
        val caption = generate(features, "What does the image describe?", maxTokens = 40, noRepeat = 3)
        val done = System.nanoTime()
        log.info(
            "Read photo in %.1f s (image %.1f s, text %.1f s, caption %.1f s)".format(
                (done - started) / 1e9, (seen - started) / 1e9, (read - seen) / 1e9, (done - read) / 1e9,
            ),
        )
        return Reading(textLines(text), caption.replace(SPECIAL_TOKENS, "").trim())
    }

    /** The image as 577 vectors: one summary token plus a 24×24 grid. */
    private fun imageFeatures(image: BufferedImage): FloatArray =
        tensor(pixelValues(image), 1, 3, IMAGE_SIZE, IMAGE_SIZE).use { pixels ->
            visionEncoder.run(mapOf("pixel_values" to pixels)).use { (it[0] as OnnxTensor).floatBuffer.toArray() }
        }

    private fun embed(ids: LongArray): FloatArray =
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            embedTokens.run(mapOf("input_ids" to input)).use { (it[0] as OnnxTensor).floatBuffer.toArray() }
        }

    /**
     * Answers [prompt] about the image: encodes image + prompt, then decodes greedily one token at a time,
     * never repeating a run of [noRepeat] tokens.
     */
    private fun generate(features: FloatArray, prompt: String, maxTokens: Int, noRepeat: Int): String {
        val inputs = features + embed(tokenizer.encode(prompt))
        val length = inputs.size / HIDDEN
        tensor(inputs, 1, length, HIDDEN).use { embeds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(length) { 1 }), longArrayOf(1, length.toLong())).use { mask ->
                encoder.run(mapOf("inputs_embeds" to embeds, "attention_mask" to mask)).use { encoded ->
                    return tokenizer.decode(decode(encoded[0] as OnnxTensor, mask, maxTokens, noRepeat))
                }
            }
        }
    }

    private fun decode(encoderStates: OnnxTensor, encoderMask: OnnxTensor, maxTokens: Int, noRepeat: Int): List<Int> {
        val tokens = mutableListOf(DECODER_START)
        var first: OrtSession.Result? = null // its cross-attention keys/values are reused by every later step
        var previous: OrtSession.Result? = null
        try {
            while (tokens.size <= maxTokens) {
                val stepTensors = listOf(
                    tensor(embed(longArrayOf(tokens.last().toLong())), 1, 1, HIDDEN),
                    OnnxTensor.createTensor(env, booleanArrayOf(previous != null)),
                )
                val inputs = HashMap<String, OnnxTensor>()
                inputs["inputs_embeds"] = stepTensors[0]
                inputs["use_cache_branch"] = stepTensors[1]
                inputs["encoder_hidden_states"] = encoderStates
                inputs["encoder_attention_mask"] = encoderMask
                for (layer in 0 until LAYERS) {
                    for (part in listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")) {
                        val source = if (part.startsWith("encoder")) first else previous
                        inputs["past_key_values.$layer.$part"] = source?.get("present.$layer.$part")?.get() as OnnxTensor? ?: noPast
                    }
                }
                val result = try {
                    decoder.run(inputs)
                } finally {
                    stepTensors.forEach { it.close() }
                }
                if (previous !== first) previous?.close()
                if (first == null) first = result
                previous = result

                val next = if (tokens.size == 1) BpeTokenizer.BOS else bestToken(result, tokens, noRepeat)
                tokens += next
                if (next == BpeTokenizer.EOS) break
            }
        } finally {
            if (previous !== first) previous?.close()
            first?.close()
        }
        return tokens
    }

    /** The most likely next token, skipping any that would repeat an earlier run of [noRepeat] tokens. */
    private fun bestToken(result: OrtSession.Result, tokens: List<Int>, noRepeat: Int): Int {
        val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
        val banned = HashSet<Int>()
        val prefix = noRepeat - 1
        if (tokens.size >= prefix) {
            val tail = tokens.subList(tokens.size - prefix, tokens.size)
            for (i in 0 until tokens.size - prefix) {
                if (tokens.subList(i, i + prefix) == tail) banned += tokens[i + prefix]
            }
        }
        var best = BpeTokenizer.EOS
        var bestScore = Float.NEGATIVE_INFINITY
        for (token in 0 until logits.limit()) {
            val score = logits.get(token)
            if (score > bestScore && token !in banned) {
                best = token
                bestScore = score
            }
        }
        return best
    }

    private fun tensor(values: FloatArray, vararg shape: Int): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape.map { it.toLong() }.toLongArray())

    override fun close() {
        listOf(noPast, visionEncoder, embedTokens, encoder, decoder, options).forEach { it.close() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Florence2::class.java)
        private const val IMAGE_SIZE = 768
        private const val HIDDEN = 768
        private const val LAYERS = 6
        private const val HEADS = 12
        private const val HEAD_SIZE = 64
        private const val DECODER_START = 2
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val SPECIAL_TOKENS = Regex("</?s>|<pad>")

        /** RGB planes scaled to 768×768 and normalized with the ImageNet mean and deviation, as in training. */
        internal fun pixelValues(image: BufferedImage): FloatArray {
            val scaled = scale(image, IMAGE_SIZE)
            val rgb = scaled.getRGB(0, 0, IMAGE_SIZE, IMAGE_SIZE, null, 0, IMAGE_SIZE)
            val plane = IMAGE_SIZE * IMAGE_SIZE
            val values = FloatArray(3 * plane)
            for (i in 0 until plane) {
                for (channel in 0 until 3) {
                    val byte = (rgb[i] shr (16 - 8 * channel)) and 0xFF
                    values[channel * plane + i] = (byte / 255f - MEAN[channel]) / STD[channel]
                }
            }
            return values
        }

        /** Scales to size×size, halving first while the image is much larger so small print stays legible. */
        private fun scale(source: BufferedImage, size: Int): BufferedImage {
            var image = redraw(source, source.width, source.height, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            while (image.width >= 2 * size || image.height >= 2 * size) {
                image = redraw(image, max(size, image.width / 2), max(size, image.height / 2), RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            }
            return redraw(image, size, size, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        }

        private fun redraw(source: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
            val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            target.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(source, 0, 0, width, height, Color.WHITE, null)
                dispose()
            }
            return target
        }

        private val REGION = Regex("""(.*?)((?:<loc_\d+>){8})""", RegexOption.DOT_MATCHES_ALL)
        private val LOCATION = Regex("""<loc_(\d+)>""")

        /**
         * Florence-2 answers the OCR prompt as "text<loc_x1><loc_y1>…<loc_y4>" per piece of text, a quadrilateral
         * in 0–999 coordinates. Pieces at the same height are joined into lines, top to bottom, left to right.
         */
        internal fun textLines(answer: String): List<String> {
            class Piece(val text: String, val left: Int, val middle: Double, val height: Int)

            val pieces = REGION.findAll(answer.replace(SPECIAL_TOKENS, "")).mapNotNull { match ->
                val text = match.groupValues[1].trim()
                val points = LOCATION.findAll(match.groupValues[2]).map { it.groupValues[1].toInt() }.toList()
                val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
                val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
                if (text.isEmpty()) null else Piece(text, xs.min(), ys.average(), max(1, ys.max() - ys.min()))
            }
            val rows = mutableListOf<MutableList<Piece>>()
            for (piece in pieces.sortedBy { it.middle }) {
                val row = rows.lastOrNull()
                if (row != null && abs(piece.middle - row.map { it.middle }.average()) <= piece.height / 2.0) row += piece else rows += mutableListOf(piece)
            }
            return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }
        }
    }
}

private fun FloatBuffer.toArray(): FloatArray = FloatArray(remaining()).also { get(it) }
