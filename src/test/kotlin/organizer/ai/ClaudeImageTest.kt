package organizer.ai

import com.anthropic.models.messages.Base64ImageSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ClaudeImageTest {
    @Test
    fun `sends JPEG and PNG photos as they are`() {
        val png = labelImage(*SAMPLE_LABEL).toPng()
        val image = ClaudeImage.of(png)
        assertEquals(Base64ImageSource.MediaType.IMAGE_PNG, image.mediaType)
        assertSame(png, image.bytes)

        val jpeg = ByteArrayOutputStream().also { ImageIO.write(labelImage("Banana"), "jpg", it) }.toByteArray()
        assertEquals(Base64ImageSource.MediaType.IMAGE_JPEG, ClaudeImage.of(jpeg).mediaType)
    }

    @Test
    fun `turns other kinds into a JPEG of at most 1600 pixels`() {
        val bmp = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(3200, 1200, BufferedImage.TYPE_INT_RGB), "bmp", it) }.toByteArray()
        val image = ClaudeImage.of(bmp)
        assertEquals(Base64ImageSource.MediaType.IMAGE_JPEG, image.mediaType)
        val decoded = ImageIO.read(ByteArrayInputStream(image.bytes))
        assertEquals(1600 to 600, decoded.width to decoded.height)
    }

    @Test
    fun `refuses what isn't an image`() {
        assertFailsWith<AiException> { ClaudeImage.of("not an image".toByteArray()) }
    }
}
