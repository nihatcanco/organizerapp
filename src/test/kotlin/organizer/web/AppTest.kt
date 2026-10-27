package organizer.web

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
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
import organizer.ai.FakeClaude
import organizer.ai.SAMPLE_LABEL
import organizer.ai.labelImage
import organizer.ai.toPng
import organizer.db.Database
import organizer.db.query
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
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

    // What the stand-in for Claude was asked.
    private val asked = mutableListOf<FakeClaude.Question>()

    private fun ApplicationTestBuilder.client(config: AppConfig = AppConfig(machineId = "test-machine")): HttpClient {
        val database = Database.open(dir.resolve("test.db"))
        application { organizer(config, database) { key -> FakeClaude(key, asked) } }
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

        val csv = client.get("/export/items.csv").bodyAsText()
        assertContains(csv, "\"Relay, 5 V\"")

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
        assertFalse(asked.last().webSearch)

        val nothing = client.submitFormWithBinaryData("/ai/fill", formData { append("name", " ") }) { sameOrigin() }
        assertEquals(HttpStatusCode.BadRequest, nothing.status)
        assertEquals(3, asked.size)
    }

    @Test
    fun `web search is switched on in Settings`() = testApplication {
        val client = client()
        client.saveApiKey("sk-ant-good-0123456789abcdefghij")
        assertFalse("name=\"web_search\" checked" in client.get("/settings").bodyAsText())
        client.submitForm("/settings/web-search", parameters { append("web_search", "on") }) { sameOrigin() }
        client.submitFormWithBinaryData("/ai/fill", formData { append("name", "AMS1117-3.3") }) { sameOrigin() }
        assertTrue(asked.last().webSearch)
        assertContains(asked.last().text, "You can search the web")
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
