package organizer.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.ErrorType
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.WebSearchTool20250305
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** Claude through Anthropic's API, as the AI that fills in items. */
interface Claude : AutoCloseable {
    /** Checks that the API key works, which costs nothing. Throws [AiException] when it doesn't. */
    fun check()

    /** Claude's answer to [question] about [image], if there is one. Throws [AiException] when there's no answer. */
    fun ask(question: String, image: ClaudeImage?, webSearch: Boolean): String
}

/** Why Claude couldn't help, in words for the page. */
class AiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A photo as Claude takes it: JPEG, PNG, GIF or WebP, up to 5 MB. */
class ClaudeImage(val mediaType: Base64ImageSource.MediaType, val bytes: ByteArray) {
    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024 // under Anthropic's 5 MB, as a margin
        private const val MAX_SIDE = 1600 // what the browser shrinks photos to before uploading

        /** [bytes] as they are when Claude takes them, otherwise shrunk and turned into a JPEG. */
        fun of(bytes: ByteArray): ClaudeImage {
            val type = mediaType(bytes)
            if (type != null && bytes.size <= MAX_BYTES) return ClaudeImage(type, bytes)
            val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                ?: throw AiException("This kind of image can't be sent to Claude; JPEG, PNG, WebP and GIF work")
            return ClaudeImage(Base64ImageSource.MediaType.IMAGE_JPEG, jpeg(image))
        }

        private fun mediaType(bytes: ByteArray): Base64ImageSource.MediaType? {
            fun startsWith(offset: Int, vararg values: Int) =
                bytes.size >= offset + values.size && values.indices.all { (bytes[offset + it].toInt() and 0xFF) == values[it] }
            return when {
                startsWith(0, 0xFF, 0xD8, 0xFF) -> Base64ImageSource.MediaType.IMAGE_JPEG
                startsWith(0, 0x89, 'P'.code, 'N'.code, 'G'.code) -> Base64ImageSource.MediaType.IMAGE_PNG
                startsWith(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) -> Base64ImageSource.MediaType.IMAGE_GIF
                startsWith(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && startsWith(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code) ->
                    Base64ImageSource.MediaType.IMAGE_WEBP
                else -> null
            }
        }

        private fun jpeg(source: BufferedImage): ByteArray {
            val scale = minOf(1.0, MAX_SIDE.toDouble() / max(source.width, source.height))
            val width = max(1, (source.width * scale).roundToInt())
            val height = max(1, (source.height * scale).roundToInt())
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(source, 0, 0, width, height, Color.WHITE, null) // JPEG has no transparency
                dispose()
            }
            return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        }
    }
}

/**
 * Claude Haiku 5.5 with thinking off: in tests on labels and typed names it was as accurate as the larger models,
 * at about $0.0003 a photo and $0.0001 a name, in about 2 seconds.
 */
class AnthropicClaude(apiKey: String) : Claude {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(60))
        .maxRetries(2)
        .build()

    override fun check() {
        call { client.models().retrieve(MODEL) }
    }

    override fun ask(question: String, image: ClaudeImage?, webSearch: Boolean): String {
        val started = System.nanoTime()
        val content = listOfNotNull(
            image?.let {
                ContentBlockParam.ofImage(
                    Base64ImageSource.builder().mediaType(it.mediaType).data(Base64.getEncoder().encodeToString(it.bytes)).build(),
                )
            },
            ContentBlockParam.ofText(question),
        )
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2000)
            .thinking(ThinkingConfigDisabled.builder().build())
            .addUserMessageOfContentBlockParams(content)
            .apply { if (webSearch) addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build()) }
        var inputTokens = 0L
        var outputTokens = 0L
        var searches = 0L
        var response: Message
        var turns = 0
        while (true) {
            response = call { client.messages().create(params.build()) }
            inputTokens += response.usage().inputTokens()
            outputTokens += response.usage().outputTokens()
            searches += response.usage().serverToolUse().map { it.webSearchRequests() }.orElse(0L)
            // A long web search can pause the answer; sending it back lets Claude carry on where it stopped.
            if (response.stopReason().orElse(null) != StopReason.PAUSE_TURN || ++turns >= MAX_CONTINUATIONS) break
            params.addMessage(response)
        }
        log.info(
            "Asked {} in {} s: {} input and {} output tokens, {} web searches",
            MODEL, "%.1f".format((System.nanoTime() - started) / 1e9), inputTokens, outputTokens, searches,
        )
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) throw AiException("Claude declined to describe this item")
        return response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
    }

    /** Runs [request], turning Anthropic's errors into messages for the page. */
    private fun <T> call(request: () -> T): T = try {
        request()
    } catch (e: UnauthorizedException) {
        throw AiException("Anthropic didn't accept the API key. Check it in Settings", e)
    } catch (e: PermissionDeniedException) {
        throw AiException("This API key isn't allowed to use $MODEL_NAME", e)
    } catch (e: NotFoundException) {
        throw AiException("$MODEL_NAME isn't available to this API key", e)
    } catch (e: RateLimitException) {
        throw AiException("Too many requests to Anthropic right now. Try again in a minute", e)
    } catch (e: InternalServerException) {
        throw AiException("Anthropic is busy or having trouble. Try again in a moment", e)
    } catch (e: AnthropicServiceException) {
        if (e.errorType().orElse(null) == ErrorType.BILLING_ERROR) throw AiException("The Anthropic account is out of credit", e)
        log.warn("Anthropic answered {}: {}", e.statusCode(), e.message)
        throw AiException("Anthropic answered with an error (${e.statusCode()})", e)
    } catch (e: AnthropicIoException) {
        throw AiException("Couldn't reach Anthropic. Check the internet connection", e)
    }

    override fun close() {
        client.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AnthropicClaude::class.java)
        const val MODEL = "claude-haiku-5-5"
        const val MODEL_NAME = "Claude Haiku 5.5"
        private const val MAX_SEARCHES = 3L
        private const val MAX_CONTINUATIONS = 3
    }
}
