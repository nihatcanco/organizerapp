package organizer.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.awt.Color
import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPhotosTest {
    private val onWhite = picture(400, 300, Color.WHITE).toPng()
    private val onTable = labelImage("NE555P").toPng()
    private val tiny = picture(100, 100, Color.WHITE).toPng()

    // A small web server on this computer, with pages and images by path.
    private val pages = mutableMapOf<String, Pair<String, ByteArray>>()
    private var requests = 0
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requests++
            val page = pages[exchange.requestURI.path]
            if (page == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", page.first)
                exchange.sendResponseHeaders(200, page.second.size.toLong())
                exchange.responseBody.use { it.write(page.second) }
            }
            exchange.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    // The server is on this computer, which the real WebPhotos refuses.
    private val webPhotos = WebPhotos(allowed = { it.isLoopbackAddress })

    @AfterTest
    fun stop() {
        server.stop(0)
        webPhotos.close()
    }

    private fun html(head: String) = "text/html; charset=utf-8" to "<!doctype html><html><head>$head</head><body>…</body></html>".toByteArray()

    @Test
    fun `takes the product photo on a plain background over an earlier one`() = runBlocking {
        pages["/table"] = html("""<meta property="og:image" content="/img/table.png">""")
        pages["/shop"] = html("""<script type="application/ld+json">{"@type": "Product", "name": "NE555P", "image": "$base/img/white.png"}</script>""")
        pages["/img/table.png"] = "image/png" to onTable
        pages["/img/white.png"] = "image/png" to onWhite

        val photo = webPhotos.find(listOf("$base/table", "$base/shop"))!!
        assertContentEquals(onWhite, photo.bytes)
        assertEquals("image/png", photo.contentType)
        assertEquals("$base/shop", photo.source)

        // Without one, the first photo found will do.
        assertContentEquals(onTable, webPhotos.find(listOf("$base/missing", "$base/table"))!!.bytes)
    }

    @Test
    fun `takes an image file's address too, but not icons or files that aren't images`() = runBlocking {
        pages["/img/white.png"] = "image/jpeg" to onWhite // the type comes from the file itself
        pages["/img/tiny.png"] = "image/png" to tiny
        pages["/img/fake.png"] = "image/png" to "<svg onload=alert(1)>".toByteArray()

        assertEquals("image/png", webPhotos.find(listOf("$base/img/white.png"))!!.contentType)
        assertNull(webPhotos.find(listOf("$base/img/tiny.png", "$base/img/fake.png")))
    }

    @Test
    fun `never fetches from this computer or the local network`() = runBlocking {
        pages["/img/white.png"] = "image/png" to onWhite
        WebPhotos().use { real ->
            assertNull(real.find(listOf("$base/img/white.png", "http://localhost:${server.address.port}/img/white.png")))
        }
        assertEquals(0, requests)

        for (address in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.20", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")) {
            assertFalse(isPublicAddress(InetAddress.getByName(address)), address)
        }
        for (address in listOf("1.1.1.1", "93.184.216.34", "2606:4700:4700::1111")) {
            assertTrue(isPublicAddress(InetAddress.getByName(address)), address)
        }
    }

    @Test
    fun `finds a page's product photos however they're written`() {
        val page = "https://shop.example/parts/ne555p".toHttpUrl()
        val html = """
            <link href="/img/link.jpg" rel="image_src">
            <meta content="https://cdn.example/og.jpg?w=800&amp;h=800" property="og:image">
            <meta name='twitter:image' content='//cdn.example/twitter.jpg'>
            <script type="application/ld+json">
              {"@context": "https://schema.org", "@graph": [
                {"@type": "WebSite", "image": "https://shop.example/logo.png"},
                {"@type": ["Product"], "image": [{"@type": "ImageObject", "url": "https://cdn.example/1.jpg"}, "https://cdn.example/2.jpg"]}
              ]}
            </script>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.example/1.jpg",
                "https://cdn.example/2.jpg",
                "https://shop.example/img/link.jpg",
                "https://cdn.example/og.jpg?w=800&h=800",
                "https://cdn.example/twitter.jpg",
            ),
            pageImages(html, page).map { it.toString() },
        )
    }

    @Test
    fun `tells plain backgrounds from busy ones`() {
        assertTrue(plainBorder(picture(400, 300, Color.WHITE)) > 0.99)
        assertTrue(plainBorder(picture(400, 300, Color(0, 0, 0, 0), type = BufferedImage.TYPE_INT_ARGB)) > 0.99)
        assertTrue(plainBorder(picture(400, 300, Color(250, 250, 248))) > 0.99)
        assertTrue(plainBorder(picture(400, 300, Color(214, 206, 190))) < 0.01)
        assertTrue(plainBorder(labelImage("NE555P")) < 0.01)
    }

    /** An item drawn in the middle of a [background]. */
    private fun picture(width: Int, height: Int, background: Color, type: Int = BufferedImage.TYPE_INT_RGB) =
        BufferedImage(width, height, type).apply {
            createGraphics().apply {
                color = background
                fillRect(0, 0, width, height)
                color = Color(30, 30, 30)
                fillRect(width / 4, height / 4, width / 2, height / 2)
                dispose()
            }
        }
}
