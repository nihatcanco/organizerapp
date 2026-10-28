package organizer.ai

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * The image in [bytes], or null when Java can't read it (WebP, HEIC, or not an image at all). A big image is read at
 * a fraction of its size, about [maxSide] pixels across at most: a small file can claim to be a huge picture, and
 * reading that in full would fill the memory.
 */
internal fun readImage(bytes: ByteArray, maxSide: Int): BufferedImage? = runCatching {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            // Every step-th pixel of every step-th row, without decoding the ones in between.
            val step = (max(reader.getWidth(0), reader.getHeight(0)) + maxSide - 1) / maxSide
            val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }
            reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()
