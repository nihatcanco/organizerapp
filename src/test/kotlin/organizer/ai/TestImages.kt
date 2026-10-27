package organizer.ai

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A photo-sized picture of a white component label with black print. */
fun labelImage(vararg lines: String): BufferedImage {
    val image = BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        color = Color(214, 206, 190)
        fillRect(0, 0, image.width, image.height)
        color = Color.WHITE
        fillRect(100, 120, 1200, 640)
        color = Color.BLACK
        font = Font(Font.SANS_SERIF, Font.PLAIN, 46)
        lines.forEachIndexed { index, line -> drawString(line, 140, 220 + index * 110) }
        dispose()
    }
    return image
}

fun BufferedImage.toPng(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(this, "png", it) }.toByteArray()

val SAMPLE_LABEL = arrayOf(
    "LCSC Part #: C25804",
    "Mfr.Part #: 0603WAF1002T5E",
    "Package: 0603",
    "Description: 10kΩ ±1% 100mW Thick Film Resistor",
    "Qty: 100 PCS",
)
