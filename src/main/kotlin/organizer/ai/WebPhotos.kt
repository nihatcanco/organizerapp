package organizer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/** A photo of an item found on the web, as JPEG, PNG, WebP or GIF, and the page it was found on. */
class WebPhoto(val contentType: String, val bytes: ByteArray, val source: String)

/** Finds a photo of an item at the pages Claude found for it. */
interface PhotoFinder : AutoCloseable {
    /** The best photo at [urls], which are web pages or image files, or null when none has a usable one. */
    suspend fun find(urls: List<String>): WebPhoto?

    override fun close() {}
}

/**
 * Downloads item photos from the web. The addresses come from web search, so anyone could have written them:
 * only addresses on the public internet are fetched, never this computer or the local network ([allowed] is
 * replaced only by tests), and downloads are size-limited.
 */
class WebPhotos(private val allowed: (InetAddress) -> Boolean = ::isPublicAddress) : PhotoFinder {
    private val client = OkHttpClient.Builder()
        // Host names that lead to a private address are refused before connecting...
        .dns(object : Dns {
            override fun lookup(hostname: String) =
                Dns.SYSTEM.lookup(hostname).filter(allowed).ifEmpty { throw UnknownHostException("$hostname isn't on the public internet") }
        })
        // ...and addresses written as numbers, which skip that lookup, before anything is sent. Redirects pass here too.
        .addNetworkInterceptor { chain ->
            val address = chain.connection()?.route()?.socketAddress?.address
            if (address == null || !allowed(address)) throw IOException("${chain.request().url.host} isn't on the public internet")
            chain.proceed(chain.request())
        }
        .connectTimeout(Duration.ofSeconds(5))
        .callTimeout(Duration.ofSeconds(10))
        .build()

    private class Found(val photo: WebPhoto, val plainBackground: Boolean)

    /** Why there's no photo at an address, for the log. */
    private class NoPhoto(reason: String) : IOException(reason)

    /** Looks at all [urls] at once and takes the first photo on a white or transparent background, otherwise the first found. */
    override suspend fun find(urls: List<String>): WebPhoto? = withContext(Dispatchers.IO) {
        val found = urls.map { url -> async { photoAt(url) } }.awaitAll().filterNotNull()
        val best = found.firstOrNull { it.plainBackground } ?: found.firstOrNull()
        if (best != null) {
            log.info("Took a photo from {} ({} KB, {} background)", best.photo.source, best.photo.bytes.size / 1024, if (best.plainBackground) "plain" else "busy")
        } else {
            log.info("No photo at any of the {} pages", urls.size)
        }
        best?.photo
    }

    /** The photo at [url]: the image itself, or the product photo of the page there. */
    private fun photoAt(url: String): Found? = try {
        val start = url.toHttpUrlOrNull() ?: throw NoPhoto("not a web address")
        when (val download = download(start)) {
            is Download.Image -> check(download.bytes, url)
            is Download.Page -> photoOnPage(download, url)
        }
    } catch (e: IOException) {
        log.info("No photo from {}: {}", url, e.message)
        null
    } catch (e: RuntimeException) {
        // A page this code can't make sense of is no reason to lose the other pages, or the rest of the answer.
        log.warn("No photo from $url: couldn't read it", e)
        null
    }

    /** The first of the [page]'s product photos that can be downloaded. */
    private fun photoOnPage(page: Download.Page, source: String): Found {
        val images = pageImages(page.html, page.url).take(3)
        if (images.isEmpty()) throw NoPhoto("the page names no product photo")
        var failure: IOException? = null
        for (image in images) {
            try {
                val download = download(image, from = page.url) as? Download.Image ?: throw NoPhoto("it's a page, not an image")
                return check(download.bytes, source)
            } catch (e: IOException) {
                failure = NoPhoto("its photo $image: ${e.message}")
            }
        }
        throw failure!!
    }

    private sealed interface Download {
        class Image(val bytes: ByteArray) : Download
        class Page(val html: String, val url: HttpUrl) : Download
    }

    /** What's at [url]; for a page's photo, [from] is that page, which image servers that stop hotlinking check. */
    private fun download(url: HttpUrl, from: HttpUrl? = null): Download {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            // Image servers that can choose prefer formats Java can read, so the background can be checked.
            .header("Accept", "text/html,image/jpeg,image/png,image/webp;q=0.8,image/gif;q=0.5")
            .apply { if (from != null) header("Referer", from.toString()) }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            // Sites that keep automated downloads out answer 403.
            if (!response.isSuccessful || body == null) throw NoPhoto("the site answered ${response.code}")
            val type = body.contentType()
            return when {
                type?.type == "image" -> Download.Image(body.limited(MAX_PHOTO_BYTES, truncate = false) ?: throw NoPhoto("the image is over 8 MB"))
                type?.subtype in setOf("html", "xhtml+xml") ->
                    Download.Page(String(body.limited(MAX_PAGE_BYTES, truncate = true)!!, type?.charset() ?: Charsets.UTF_8), response.request.url)
                else -> throw NoPhoto("it's ${type ?: "of no type"}, not a page or an image")
            }
        }
    }

    /** The photo in [bytes] if it is one, big enough not to be an icon or a logo. */
    private fun check(bytes: ByteArray, source: String): Found {
        val type = photoType(bytes) ?: throw NoPhoto("not a JPEG, PNG, WebP or GIF image")
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        return when {
            // Java can't read WebP, so its size and background stay unknown.
            image == null && bytes.size < MIN_UNREADABLE_BYTES -> throw NoPhoto("the image is too small to be a photo")
            image == null -> Found(WebPhoto(type, bytes, source), plainBackground = false)
            min(image.width, image.height) < MIN_SIDE -> throw NoPhoto("the image is only ${image.width}×${image.height}, an icon or a logo")
            else -> Found(WebPhoto(type, bytes, source), plainBorder(image) >= PLAIN)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        val log = LoggerFactory.getLogger(WebPhotos::class.java)
        const val USER_AGENT = "Organizer/0.1 (home inventory app, fetching a product photo)"
        const val MAX_PAGE_BYTES = 2L * 1024 * 1024 // product photos are named near the top
        const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
        const val MIN_SIDE = 200
        const val MIN_UNREADABLE_BYTES = 10_000
        const val PLAIN = 0.9
    }
}

/** At most [limit] bytes; a longer body is cut off when [truncate] is set, otherwise refused. */
private fun ResponseBody.limited(limit: Long, truncate: Boolean): ByteArray? {
    val source = source()
    source.request(limit + 1)
    val size = source.buffer.size
    return if (size > limit && !truncate) null else source.buffer.readByteArray(min(size, limit))
}

/** The type of the image in [bytes], when it's one that browsers show and the item form takes. */
private fun photoType(bytes: ByteArray): String? {
    fun startsWith(offset: Int, text: String) =
        bytes.size >= offset + text.length && text.indices.all { (bytes[offset + it].toInt() and 0xFF) == text[it].code }
    return when {
        startsWith(0, "ÿØÿ") -> "image/jpeg"
        startsWith(0, "\u0089PNG") -> "image/png"
        startsWith(0, "GIF8") -> "image/gif"
        startsWith(0, "RIFF") && startsWith(8, "WEBP") -> "image/webp"
        else -> null
    }
}

/**
 * How much of the [image]'s border is white or see-through, from 0 to 1. Product photos on a plain
 * background score close to 1; photos of an item on a table or in someone's hand score far lower.
 */
internal fun plainBorder(image: BufferedImage): Double {
    val band = max(1, min(image.width, image.height) / 50)
    val stepX = max(1, image.width / 100)
    val stepY = max(1, image.height / 100)
    var plain = 0
    var total = 0
    fun sample(x: Int, y: Int) {
        total++
        if (isPlain(image.getRGB(x, y))) plain++
    }
    for (depth in 0 until band) {
        for (x in 0 until image.width step stepX) {
            sample(x, depth)
            sample(x, image.height - 1 - depth)
        }
        for (y in 0 until image.height step stepY) {
            sample(depth, y)
            sample(image.width - 1 - depth, y)
        }
    }
    return plain.toDouble() / total
}

/** Nearly white and without a tint, or mostly transparent. */
private fun isPlain(argb: Int): Boolean {
    if (argb ushr 24 < 32) return true
    val red = argb shr 16 and 0xFF
    val green = argb shr 8 and 0xFF
    val blue = argb and 0xFF
    val lowest = minOf(red, green, blue)
    return lowest >= 235 && maxOf(red, green, blue) - lowest <= 16
}

private val LD_JSON = Regex("""<script\b[^>]*application/ld\+json[^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("""<(meta|link)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val PREVIEW_IMAGES = setOf("og:image", "og:image:secure_url", "og:image:url", "twitter:image", "twitter:image:src")

/**
 * The photos a product page names, best first: the images in its product data, then its preview image for
 * links shared elsewhere, which on some sites is a logo instead.
 */
internal fun pageImages(html: String, page: HttpUrl): List<HttpUrl> {
    val found = mutableListOf<String>()
    for (script in LD_JSON.findAll(html)) {
        runCatching { Json.parseToJsonElement(script.groupValues[1].trim()) }.getOrNull()?.let { productImages(it, found) }
    }
    for (tag in TAG.findAll(html)) {
        val attributes = ATTRIBUTE.findAll(tag.value).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
        }
        val image = when (tag.groupValues[1].lowercase()) {
            "meta" -> attributes["content"].takeIf { (attributes["property"] ?: attributes["name"])?.lowercase() in PREVIEW_IMAGES }
            else -> attributes["href"].takeIf { attributes["rel"]?.lowercase() == "image_src" }
        }
        image?.let(found::add)
    }
    // An empty address would resolve to the page itself.
    return found.map { unescapeHtml(it).trim() }.filter { it.isNotEmpty() }.mapNotNull { page.resolve(it) }.distinct()
}

/** Adds the images of every product in schema.org data to [found]. */
private fun productImages(element: JsonElement, found: MutableList<String>) {
    when (element) {
        is JsonArray -> element.forEach { productImages(it, found) }
        is JsonObject -> {
            val type = element["@type"]
            val types = if (type is JsonArray) type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } else listOfNotNull((type as? JsonPrimitive)?.contentOrNull)
            if ("Product" in types) imageUrls(element["image"], found)
            element["@graph"]?.let { productImages(it, found) }
        }
        else -> Unit
    }
}

/** An image is written as an address, an ImageObject, or a list of either. */
private fun imageUrls(element: JsonElement?, found: MutableList<String>) {
    when (element) {
        is JsonPrimitive -> element.contentOrNull?.let(found::add)
        is JsonArray -> element.forEach { imageUrls(it, found) }
        is JsonObject -> imageUrls(element["url"] ?: element["contentUrl"], found)
        else -> Unit
    }
}

private val ENTITY = Regex("""&(#\d+|#x[0-9a-fA-F]+|amp|quot|apos|lt|gt);""")

private fun unescapeHtml(text: String): String = ENTITY.replace(text) { match ->
    when (val name = match.groupValues[1]) {
        "amp" -> "&"
        "quot" -> "\""
        "apos" -> "'"
        "lt" -> "<"
        "gt" -> ">"
        else -> {
            val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
        }
    }
}

/** Whether [address] is on the public internet: not this computer, the local network, or a provider's shared range. */
internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) {
        return false
    }
    val bytes = address.address.map { it.toInt() and 0xFF }
    return if (address is Inet4Address) {
        bytes[0] != 0 && !(bytes[0] == 100 && bytes[1] and 0xC0 == 64) // 0.0.0.0/8, and 100.64.0.0/10 behind carrier NAT
    } else {
        bytes[0] and 0xFE != 0xFC // fc00::/7, the IPv6 local network
    }
}
