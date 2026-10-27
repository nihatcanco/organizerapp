package organizer.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import organizer.model.AiSpending
import organizer.store.SecretBox
import organizer.store.SettingsRepository
import organizer.store.UsageRepository
import java.time.Instant

/**
 * Filling in items with Claude, once an Anthropic API key is saved in Settings. The key is checked with Anthropic,
 * then stored encrypted by [secrets]; pages only ever see its last four characters. To find an item's photo,
 * Claude searches the web and [photos] downloads one from the pages it found.
 */
class Ai(
    private val settings: SettingsRepository,
    private val secrets: SecretBox,
    private val usage: UsageRepository,
    private val connect: (apiKey: String) -> Claude,
    private val photos: PhotoFinder,
) : AutoCloseable {

    sealed interface Key {
        data object Missing : Key

        /** Saved on another computer, or before the key file was lost, so it can't be decrypted here. */
        data object Unreadable : Key

        /** Saved and usable; [ending] is its last four characters. */
        data class Saved(val ending: String) : Key
    }

    /** Whether saved keys can only be decrypted on this computer (see [SecretBox]). */
    val tiedToMachine: Boolean get() = secrets.tiedToMachine

    private val lock = Mutex()
    private var client: Pair<String, Claude>? = null

    suspend fun key(): Key {
        val sealed = settings.get(API_KEY) ?: return Key.Missing
        val key = secrets.open(API_KEY, sealed) ?: return Key.Unreadable
        return Key.Saved(key.takeLast(4))
    }

    suspend fun enabled(): Boolean = key() is Key.Saved

    /** What questions to Claude have cost since [from], or ever. */
    suspend fun spending(from: Instant? = null): AiSpending = usage.spending(from)

    /** Checks [apiKey] with Anthropic, then saves it encrypted. Throws [AiException] when it can't be used. */
    suspend fun saveKey(apiKey: String) {
        val key = apiKey.trim()
        if (!KEY_PATTERN.matches(key)) throw AiException("That isn't an Anthropic API key. They start with sk-ant-")
        withContext(Dispatchers.IO) { connect(key).use { it.check() } }
        lock.withLock {
            settings.set(API_KEY, secrets.seal(API_KEY, key))
            dropClient()
        }
    }

    suspend fun removeKey() {
        lock.withLock {
            settings.remove(API_KEY)
            dropClient()
        }
    }

    /**
     * What Claude makes of an item from its [photo], its typed [name], or both, from what it knows: no web search,
     * so it costs a fraction of a cent. Throws [AiException] when there is no key, or Claude can't answer.
     */
    suspend fun fill(photo: ByteArray?, name: String?): ItemSuggestion {
        require(photo != null || name != null) { "Needs a photo or a name" }
        val image = photo?.let { withContext(Dispatchers.Default) { ClaudeImage.of(it) } }
        val answer = ask(itemQuestion(name, image != null), image, webSearch = false)
        if (answer.refused) throw AiException("Claude declined to describe this item")
        return suggestItem(answer.text)
    }

    /** What [findPhoto] found: the [photo], if any, and the [pages] Claude named for it. */
    class PhotoSearch(val photo: WebPhoto?, val pages: List<String>)

    /**
     * A photo of the item called [name]: Claude searches the web for pages that show it, then [photos] downloads one.
     * The searches cost about 1¢ each. Throws [AiException] when there is no key, or Claude can't answer.
     */
    suspend fun findPhoto(name: String): PhotoSearch {
        val answer = ask(photoQuestion(name), image = null, webSearch = true)
        if (answer.refused) throw AiException("Claude declined to look for this item")
        val pages = photoPages(answer.text)
        if (pages.isEmpty()) {
            // The whole answer, to tell an empty list from addresses in a form that couldn't be read.
            log.info("Claude named no pages with a photo of {}. Its answer: {}", name, answer.text.take(2000))
            return PhotoSearch(null, pages)
        }
        log.info("Looking for a photo of {} at {}", name, pages.joinToString(", "))
        val photo = try {
            photos.find(pages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Looking for a photo of $name failed", e)
            null
        }
        return PhotoSearch(photo, pages)
    }

    /** Asks Claude, and counts what that cost. */
    private suspend fun ask(question: String, image: ClaudeImage?, webSearch: Boolean): Answer {
        val claude = client() ?: throw AiException("Add an Anthropic API key in Settings first")
        val answer = withContext(Dispatchers.IO) { claude.ask(question, image, webSearch) }
        usage.record(AnthropicClaude.MODEL, answer.usage)
        return answer
    }

    /** One client per key, kept for its connections; a new key replaces it. */
    private suspend fun client(): Claude? {
        val sealed = settings.get(API_KEY) ?: return null
        val key = secrets.open(API_KEY, sealed) ?: return null
        return lock.withLock {
            client?.takeIf { it.first == key }?.second ?: run {
                dropClient()
                connect(key).also { client = key to it }
            }
        }
    }

    private fun dropClient() {
        client?.second?.close()
        client = null
    }

    override fun close() {
        dropClient()
        photos.close()
    }

    private companion object {
        val log = LoggerFactory.getLogger(Ai::class.java)
        const val API_KEY = "anthropic_api_key"
        val KEY_PATTERN = Regex("""sk-ant-[A-Za-z0-9_-]{20,500}""")
    }
}
