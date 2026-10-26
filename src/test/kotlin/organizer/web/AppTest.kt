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
import org.junit.jupiter.api.Assumptions.assumeTrue
import organizer.AppConfig
import organizer.ai.SAMPLE_LABEL
import organizer.ai.labelImage
import organizer.ai.testModelDirectory
import organizer.ai.toPng
import organizer.db.Database
import java.nio.file.Path
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

    private fun ApplicationTestBuilder.client(config: AppConfig = AppConfig(), models: Path = dir.resolve("models")): HttpClient {
        val database = Database.open(dir.resolve("test.db"))
        application { organizer(config.copy(modelsPath = models), database) }
        return createClient { followRedirects = false }
    }

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
    fun `photo reading waits until its model is downloaded`() = testApplication {
        val client = client()
        val status = client.get("/ai/status").bodyAsText()
        assertContains(status, "\"state\":\"missing\"")
        assertContains(status, "\"total\":550067409")
        val read = client.submitFormWithBinaryData("/ai/read", formData { append("item", "1") }) { sameOrigin() }
        assertEquals(HttpStatusCode.Conflict, read.status)
        assertContains(client.get("/items/new").bodyAsText(), "Fill in from photo")
    }

    @Test
    fun `photo reading can be switched off`() = testApplication {
        val client = client(AppConfig(ai = false))
        assertEquals(HttpStatusCode.NotFound, client.get("/ai/status").status)
        assertFalse("Fill in from photo" in client.get("/items/new").bodyAsText())
    }

    /** Needs the real model: ./gradlew test -Dorganizer.test.models=path/to/florence-2-base-ft */
    @Test
    fun `fills in item details from an uploaded photo`() = testApplication {
        val models = testModelDirectory()
        assumeTrue(models != null, "no model directory given")
        val client = client(models = models!!.parent)
        val response = client.submitFormWithBinaryData("/ai/read", formData {
            append("photo", labelImage(*SAMPLE_LABEL).toPng(), Headers.build {
                append(HttpHeaders.ContentType, "image/png")
                append(HttpHeaders.ContentDisposition, "filename=\"label.png\"")
            })
        }) { sameOrigin() }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = response.bodyAsText()
        assertContains(json, "\"name\":\"Resistor 10 kΩ\"")
        assertContains(json, "\"quantity\":100")
        assertContains(json, "{\"name\":\"Package\",\"value\":\"0603\"}")
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
