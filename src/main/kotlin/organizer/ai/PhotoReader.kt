package organizer.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream

/**
 * Suggests item details from photos using Florence-2, which runs inside the app.
 * The model files (about 550 MB) are downloaded once, when first asked for, into [dir].
 */
class PhotoReader(private val dir: Path) : AutoCloseable {

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val done: Long) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private class ModelFile(val path: String, val size: Long, val sha256: String) {
        val name: String get() = path.substringAfterLast('/')
    }

    val totalBytes: Long = FILES.sumOf { it.size }

    private val state = AtomicReference<Status>(if (FILES.all(::isPresent)) Status.Ready else Status.Missing)
    private val downloaded = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLock = Mutex()
    private var model: Florence2? = null
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun status(): Status = when (val current = state.get()) {
        is Status.Downloading -> Status.Downloading(downloaded.get())
        else -> current
    }

    /** Starts fetching the model in the background, unless it is already here or on its way. */
    fun startDownload() {
        val current = state.get()
        if (current == Status.Ready || current is Status.Downloading) return
        if (!state.compareAndSet(current, Status.Downloading(0))) return
        downloaded.set(0)
        scope.launch {
            state.set(
                try {
                    dir.createDirectories()
                    FILES.forEach(::download)
                    log.info("Photo-reading model downloaded to $dir")
                    Status.Ready
                } catch (e: Exception) {
                    log.warn("Downloading the photo-reading model failed", e)
                    Status.Failed(e.message ?: e.toString())
                },
            )
        }
    }

    /** Reads one photo at a time: the model uses several CPU cores and a few hundred MB of memory. */
    suspend fun read(image: BufferedImage): ItemSuggestion = modelLock.withLock {
        check(state.get() == Status.Ready) { "The photo-reading model isn't downloaded yet." }
        withContext(Dispatchers.Default) {
            val loaded = model ?: try {
                Florence2(dir).also { model = it }
            } catch (e: Exception) {
                // Damaged files: remove them so the next download starts afresh.
                FILES.forEach { dir.resolve(it.name).deleteIfExists() }
                state.set(Status.Failed("The model files were damaged and have been removed. Please download them again."))
                throw e
            }
            val reading = loaded.read(image)
            suggestItem(reading.lines, reading.caption)
        }
    }

    private fun isPresent(file: ModelFile): Boolean = dir.resolve(file.name).let { it.exists() && it.fileSize() == file.size }

    private fun download(file: ModelFile) {
        val target = dir.resolve(file.name)
        if (isPresent(file)) {
            downloaded.addAndGet(file.size)
            return
        }
        val request = HttpRequest.newBuilder(URI("$SOURCE/${file.path}")).timeout(Duration.ofMinutes(30)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("Downloading ${file.name} failed (HTTP ${response.statusCode()}).")
        }
        val partial = dir.resolve(file.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    downloaded.addAndGet(count.toLong())
                }
            }
        }
        if (HexFormat.of().formatHex(digest.digest()) != file.sha256) {
            partial.deleteIfExists()
            throw IOException("${file.name} arrived damaged (checksum mismatch). Please try again.")
        }
        partial.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun close() {
        scope.cancel()
        model?.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(PhotoReader::class.java)

        // Florence-2-base (MIT licence) converted to ONNX, pinned to one upload so the checksums below hold.
        private const val SOURCE =
            "https://huggingface.co/onnx-community/Florence-2-base-ft/resolve/e88a44eaf3791a35eae0c5a47b3dbcd36e67eb6f"

        // The image encoder stays at full precision: the 8-bit version misreads noticeably more characters.
        private val FILES = listOf(
            ModelFile("tokenizer.json", 2_297_961, "d69dcdb2323e124ac4f800cb9863ddccea0d7bb11e16125e8df3bd60f2f8aeac"),
            ModelFile("onnx/vision_encoder.onnx", 366_549_825, "d67258cdfdebfa21285dad9e7bd4bd99725236d0aaef9e474a1b24a6ec471351"),
            ModelFile("onnx/embed_tokens_quantized.onnx", 39_390_433, "6b2258db1c8ee9b160576ccde3cd3814d83a2edaed0dd1c6ca9ff3c38fa62214"),
            ModelFile("onnx/encoder_model_quantized.onnx", 43_651_493, "f4ad7a68f1fb875d3bcf735ea14a7021b7ba7e83baf7cf10289881b4ed6d9b85"),
            ModelFile("onnx/decoder_model_merged_quantized.onnx", 98_177_697, "f22f52f980c33df0efa15932c2f3db6d9d3595ce6387eca938b8cfe23dc4c641"),
        )
    }
}
