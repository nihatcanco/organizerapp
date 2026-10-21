package organizer.web

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import kotlin.test.Test
import kotlin.test.assertEquals

class QrTest {
    @Test
    fun `label QR codes decode back to their URL`() {
        val url = "http://192.168.1.20:8080/l/42"
        val svg = qrSvg(url)

        // Rebuild the module grid from the SVG path, then let ZXing read it.
        val quietZone = 4
        val size = Regex("""viewBox="0 0 (\d+) \d+"""").find(svg)!!.groupValues[1].toInt()
        val matrix = BitMatrix(size - 2 * quietZone)
        val path = Regex(""" d="([^"]+)"""").find(svg)!!.groupValues[1]
        for (run in Regex("""M(\d+) (\d+)h(\d+)v1h-\d+z""").findAll(path)) {
            val (x, y, length) = run.destructured.toList().map { it.toInt() }
            for (dx in 0 until length) matrix.set(x - quietZone + dx, y - quietZone)
        }
        assertEquals(url, Decoder().decode(matrix).text)
    }
}
