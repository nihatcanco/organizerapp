package organizer.web

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import organizer.AppConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import organizer.ai.FakeClaude
import organizer.ai.FakePhotos
import organizer.ai.SAMPLE_LABEL
import organizer.ai.WebPhoto
import organizer.ai.labelImage
import organizer.ai.toPng
import organizer.db.Database
import organizer.db.query
import java.sql.DriverManager
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppTest {
    private val dir = createTempDirectory("organizer-web-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    // What the stand-in for Claude was asked, and what it answers.
    private val asked = mutableListOf<FakeClaude.Question>()
    private var answer = """{"name": "Resistor 10 kΩ", "quantity": 100, "tags": ["resistor"], "properties": {"Package": "0603"}}"""

    // The photo that the stand-in for the web has at every page.
    private val webPhoto = WebPhoto("image/png", labelImage("NE555P").toPng(), "https://www.ti.com/product/NE555")
    private val photos = FakePhotos(webPhoto)

    private fun ApplicationTestBuilder.client(config: AppConfig = AppConfig(machineId = "test-machine")): HttpClient {
        val database = Database.open(dir.resolve("test.db"))
        application { organizer(config, database, photos) { key -> FakeClaude(key, asked) { answer } } }
        return createClient { followRedirects = false }
    }

    private suspend fun HttpClient.saveApiKey(key: String) = submitForm("/settings/api-key", parameters { append("api_key", key) }) { sameOrigin() }

    // Browsers send Host and Origin with every form post, and the CSRF check compares them.
    // The test client sends neither on its own.
    private fun HttpRequestBuilder.sameOrigin(origin: String = "http://localhost") {
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, origin)
    }

    private suspend fun HttpClient.createLocation(name: String, parent: Long? = null): String {
        val response = submitForm("/locations", parameters {
            append("name", name)
            append("parent", parent?.toString().orEmpty())
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.SeeOther, response.status)
        return response.headers[HttpHeaders.Location]!!
    }

    @Test
    fun `a new organizer shows the welcome page`() = testApplication {
        val response = client().get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "Welcome to your organizer")
    }

    @Test
    fun `create a cabinet of drawers, add an item with a photo, then find it`() = testApplication {
        val client = client()
        val cabinetId = client.createLocation("Cabinet").substringAfterLast('/').toLong()
        assertEquals("/locations/$cabinetId?created=3", client.createLocation("Drawer {1-3}", parent = cabinetId))
        assertContains(client.get("/locations/$cabinetId").bodyAsText(), "Drawer 3")

        val drawer1 = cabinetId + 1
        val created = client.submitFormWithBinaryData("/items", formData {
            append("name", "LM317T")
            append("quantity", "4")
            append("location", drawer1.toString())
            append("tags", "regulator, TO-220")
            append("property_name", "Package")
            append("property_value", "TO-220")
            append("photo", byteArrayOf(9, 9, 9), Headers.build {
                append(HttpHeaders.ContentType, "image/png")
                append(HttpHeaders.ContentDisposition, "filename=\"part.png\"")
            })
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.SeeOther, created.status)
        val itemPath = created.headers[HttpHeaders.Location]!!

        val itemPage = client.get(itemPath).bodyAsText()
        assertContains(itemPage, "Cabinet / Drawer 1")
        assertContains(itemPage, "TO-220")
        assertEquals(listOf<Byte>(9, 9, 9), client.get("$itemPath/photo").bodyAsBytes().toList())

        val results = client.get("/search?q=317").bodyAsText()
        assertContains(results, "LM317T")
        assertContains(results, "1 item")
        assertFalse("LM317T" in client.get("/search?q=555").bodyAsText())
    }

    @Test
    fun `the home page doesn't list an item twice`() = testApplication {
        val client = client()
        val low = client.submitForm("/items", parameters {
            append("name", "Fuse")
            append("quantity", "1")
            append("min_quantity", "2")
        }) { sameOrigin() }.headers[HttpHeaders.Location]!!
        client.submitForm("/items", parameters { append("name", "Relay") }) { sameOrigin() }

        val home = client.get("/").bodyAsText()
        assertContains(home, "Running low")
        assertContains(home, "Relay")
        assertEquals(1, Regex("""href="$low"""").findAll(home).count())
    }

    @Test
    fun `a location's page adds items to it and says which drawers are empty`() = testApplication {
        val client = client()
        val cabinetId = client.createLocation("Cabinet").substringAfterLast('/').toLong()
        client.createLocation("Drawer {1-2}", parent = cabinetId)

        val page = client.get("/locations/$cabinetId").bodyAsText()
        val header = Regex("""<nav class="topnav">.*?</nav>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.value
        assertContains(header, "href=\"/items/new?location=$cabinetId\"")
        assertContains(page, "<span>Empty</span>")
    }

    @Test
    fun `quantity buttons redirect forms and answer scripts with JSON`() = testApplication {
        val client = client()
        val item = client.submitForm("/items", parameters {
            append("name", "Fuse")
            append("quantity", "2")
            append("min_quantity", "1")
        }) { sameOrigin() }.headers[HttpHeaders.Location]!!

        val form = client.submitForm("$item/quantity", parameters {
            append("delta", "-1")
            append("return", "/search")
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.SeeOther, form.status)
        assertEquals("/search", form.headers[HttpHeaders.Location])

        val script = client.submitForm("$item/quantity", parameters { append("set", "7") }) {
            sameOrigin()
            header(HttpHeaders.Accept, "application/json")
        }
        assertEquals("""{"quantity":7,"low":false}""", script.bodyAsText())
    }

    @Test
    fun `an invalid item form explains the problem and keeps the input`() = testApplication {
        val response = client().submitForm("/items", parameters {
            append("name", "")
            append("quantity", "lots")
            append("notes", "keep me")
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val page = response.bodyAsText()
        assertContains(page, "Give the item a name.")
        assertContains(page, "Enter a whole number")
        assertContains(page, "keep me")
    }

    @Test
    fun `a photo over the size limit gets a page saying so`() = testApplication {
        val response = client().submitFormWithBinaryData("/items", formData {
            append("name", "Huge")
            append("photo", ByteArray(MAX_UPLOAD_BYTES.toInt() + 1), Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"huge.jpg\"")
            })
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertContains(response.bodyAsText(), "That photo is too large")
    }

    @Test
    fun `posts from other websites are rejected`() = testApplication {
        val response = client().submitForm("/locations", parameters { append("name", "Sneaky") }) {
            sameOrigin(origin = "http://evil.example")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "Cross-site request validation failed")
    }

    @Test
    fun `a password protects every page`() = testApplication {
        val client = client(AppConfig(password = "secret"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/") { basicAuth("me", "wrong") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/") { basicAuth("me", "secret") }.status)
    }

    @Test
    fun `after too many wrong passwords even the right one has to wait`() = testApplication {
        val client = client(AppConfig(password = "secret"))
        repeat(PasswordGuesses.MAX_WRONG) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/") { basicAuth("me", "guess $it") }.status)
        }
        val response = client.get("/") { basicAuth("me", "secret") }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("60", response.headers[HttpHeaders.RetryAfter])
        assertContains(response.bodyAsText(), "Too many wrong passwords")
    }

    @Test
    fun `HEAD requests are answered like GET`() = testApplication {
        assertEquals(HttpStatusCode.OK, client().head("/").status)
    }

    @Test
    fun `labels hold QR codes that link to the public address`() = testApplication {
        val client = client(AppConfig(publicUrl = "http://organizer.lan:8080"))
        val cabinet = client.createLocation("Cabinet")
        val page = client.get("/labels?location=${cabinet.substringAfterLast('/')}").bodyAsText()
        assertContains(page, "<svg")
        assertContains(page, "http://organizer.lan:8080")
        assertEquals("/locations/1", client.get("/l/1").headers[HttpHeaders.Location])
    }

    @Test
    fun `exports produce CSV and a SQLite backup`() = testApplication {
        val client = client()
        client.submitForm("/items", parameters { append("name", "Relay, 5 V") }) { sameOrigin() }
        client.submitForm("/items", parameters { append("name", "=HYPERLINK(\"http://evil.example\")") }) { sameOrigin() }

        val csv = client.get("/export/items.csv").bodyAsText()
        assertContains(csv, "\"Relay, 5 V\"")
        // Spreadsheets would run it as a formula without the ' in front.
        assertContains(csv, "\"'=HYPERLINK(\"\"http://evil.example\"\")\"")

        val backup = client.get("/export/backup")
        assertEquals(HttpStatusCode.OK, backup.status)
        assertTrue(backup.bodyAsBytes().decodeToString(0, 15) == "SQLite format 3")
    }

    @Test
    fun `the item form can take a photo with the camera`() = testApplication {
        val form = client().get("/items/new").bodyAsText()
        assertContains(form, "data-take-photo")
        assertContains(form, "<dialog class=\"camera\"")
        val captureInput = Regex("""<input[^>]*capture="environment"[^>]*>""").find(form)?.value
        assertNotNull(captureInput)
        // Without a name it isn't uploaded; app.js moves its photo into the "photo" input.
        assertFalse("name=" in captureInput)
    }

    @Test
    fun `without an API key the item form points to Settings`() = testApplication {
        val client = client()
        val form = client.get("/items/new").bodyAsText()
        assertFalse("Fill in with AI" in form)
        assertContains(form, "href=\"/settings\"")
        val fill = client.submitFormWithBinaryData("/ai/fill", formData { append("name", "NE555P") }) { sameOrigin() }
        assertEquals(HttpStatusCode.Conflict, fill.status)
        assertFalse("data-find-photo" in form)
        val find = client.submitFormWithBinaryData("/ai/photo", formData { append("name", "NE555P") }) { sameOrigin() }
        assertEquals(HttpStatusCode.Conflict, find.status)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an API key is checked, stored encrypted and never shown again`() = testApplication {
        val client = client()
        val key = "sk-ant-good-0123456789abcdefghij-wxyz"

        val rejected = client.saveApiKey("sk-ant-bad-0123456789abcdefghijklmn")
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertContains(rejected.bodyAsText(), "accept the API key. Check it in Settings.")
        assertEquals(HttpStatusCode.BadRequest, client.saveApiKey("not a key").status)

        val saved = client.saveApiKey(key)
        assertEquals(HttpStatusCode.SeeOther, saved.status)
        val page = client.get("/settings").bodyAsText()
        assertContains(page, "sk-ant-…wxyz")
        assertFalse(key in page)
        assertContains(client.get("/items/new").bodyAsText(), "Fill in with AI")

        val stored = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("test.db")}").use { c ->
            c.query("SELECT value FROM settings WHERE name = 'anthropic_api_key'") { it.getString(1) }.single()
        }
        assertFalse(key in stored)
        assertFalse("0123456789abcdefghij" in stored)

        assertEquals(HttpStatusCode.SeeOther, client.submitForm("/settings/api-key/remove") { sameOrigin() }.status)
        assertFalse("Fill in with AI" in client.get("/items/new").bodyAsText())
    }

    @Test
    fun `fills in items from a photo, a typed name, or both`() = testApplication {
        val client = client()
        client.saveApiKey("sk-ant-good-0123456789abcdefghij")
        val photo = labelImage(*SAMPLE_LABEL).toPng()
        val photoHeaders = Headers.build {
            append(HttpHeaders.ContentType, "image/png")
            append(HttpHeaders.ContentDisposition, "filename=\"label.png\"")
        }

        val fromPhoto = client.submitFormWithBinaryData("/ai/fill", formData { append("photo", photo, photoHeaders) }) { sameOrigin() }
        assertEquals(HttpStatusCode.OK, fromPhoto.status)
        assertContains(fromPhoto.bodyAsText(), "\"quantity\":100")
        assertContains(fromPhoto.bodyAsText(), "{\"name\":\"Package\",\"value\":\"0603\"}")
        assertNotNull(asked.last().image)
        assertContains(asked.last().text, "This photo shows something")

        client.submitFormWithBinaryData("/ai/fill", formData { append("name", "NE555P") }) { sameOrigin() }
        assertEquals(null, asked.last().image)
        assertContains(asked.last().text, "Someone is adding \"NE555P\"")

        client.submitFormWithBinaryData("/ai/fill", formData {
            append("name", "Resistor 10k")
            append("photo", photo, photoHeaders)
        }) { sameOrigin() }
        assertNotNull(asked.last().image)
        assertContains(asked.last().text, "This photo shows \"Resistor 10k\"")

        val nothing = client.submitFormWithBinaryData("/ai/fill", formData { append("name", " ") }) { sameOrigin() }
        assertEquals(HttpStatusCode.BadRequest, nothing.status)
        assertEquals(3, asked.size)
    }

    @Test
    fun `Find photo looks the name up on the web, filling in doesn't`() = testApplication {
        val client = client()
        client.saveApiKey("sk-ant-good-0123456789abcdefghij")
        client.submitFormWithBinaryData("/ai/fill", formData { append("name", "NE555P") }) { sameOrigin() }
        assertFalse(asked.last().webSearch)
        assertTrue(photos.searched.isEmpty())

        answer = """{"photos": ["https://www.ti.com/product/NE555", "https://www.mouser.com/ProductDetail/NE555P"]}"""
        val found = client.submitFormWithBinaryData("/ai/photo", formData { append("name", "NE555P") }) { sameOrigin() }
        assertTrue(asked.last().webSearch)
        assertContains(asked.last().text, "Find web pages with a photo of \"NE555P\"")
        val photo = Json.parseToJsonElement(found.bodyAsText()).jsonObject["photo"]!!.jsonObject
        assertEquals("image/png", photo["type"]!!.jsonPrimitive.content)
        assertContentEquals(webPhoto.bytes, Base64.getDecoder().decode(photo["data"]!!.jsonPrimitive.content))
        assertEquals("https://www.ti.com/product/NE555", photo["source"]!!.jsonPrimitive.content)
        assertEquals(listOf(listOf("https://www.ti.com/product/NE555", "https://www.mouser.com/ProductDetail/NE555P")), photos.searched)

        // Without a photo, the status line says why.
        photos.photo = null
        val blocked = client.submitFormWithBinaryData("/ai/photo", formData { append("name", "NE555P") }) { sameOrigin() }
        assertContains(blocked.bodyAsText(), "\"note\":\"Couldn't download a photo from ti.com, mouser.com; Settings › Logs says why.\"")
        photos.error = IllegalStateException("broken page")
        val failed = client.submitFormWithBinaryData("/ai/photo", formData { append("name", "NE555P") }) { sameOrigin() }
        assertEquals(HttpStatusCode.OK, failed.status)
        assertContains(failed.bodyAsText(), "Couldn't download a photo from ti.com")
        photos.error = null
        answer = """{"photos": []}"""
        val nowhere = client.submitFormWithBinaryData("/ai/photo", formData { append("name", "NE555P") }) { sameOrigin() }
        assertContains(nowhere.bodyAsText(), "\"note\":\"Claude found no page with a photo of this exact item.\"")
        assertEquals(3, photos.searched.size)
        val noName = client.submitFormWithBinaryData("/ai/photo", formData { append("name", " ") }) { sameOrigin() }
        assertEquals(HttpStatusCode.BadRequest, noName.status)

        // The Logs page shows where the photo was looked for.
        val logs = client.get("/settings/logs").bodyAsText()
        assertContains(logs, "Looking for a photo of NE555P at https://www.ti.com/product/NE555, https://www.mouser.com/ProductDetail/NE555P")
        assertContains(logs, "Claude named no pages with a photo of NE555P")
        val problems = client.get("/settings/logs?show=problems").bodyAsText()
        assertFalse("Looking for a photo of NE555P at" in problems)
        assertContains(problems, "Looking for a photo of NE555P failed")

        assertContains(client.get("/items/new").bodyAsText(), "data-find-photo")
    }

    @Test
    fun `Settings names the model and adds up what AI has cost`() = testApplication {
        val client = client()
        client.saveApiKey("sk-ant-good-0123456789abcdefghij")
        val before = client.get("/settings").bodyAsText()
        assertContains(before, "Claude Haiku 5.5 <code>claude-haiku-5-5</code>")
        assertContains(before, "<code>claude-haiku-5-5</code>")
        assertContains(before, "Nothing yet")
        assertFalse("web_search" in before)

        repeat(2) { client.submitFormWithBinaryData("/ai/fill", formData { append("name", "NE555P") }) { sameOrigin() } }
        val after = client.get("/settings").bodyAsText()
        assertContains(after, "$0.0255 this month, $0.0255 in total")
        assertContains(after, "2 questions and 2 web searches since")
    }

    @Test
    fun `unknown pages get a friendly 404`() = testApplication {
        val client = client()
        for (path in listOf("/nope", "/items/123", "/locations/abc")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertContains(response.bodyAsText(), "Nothing here")
        }
    }
}
