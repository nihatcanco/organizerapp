package organizer

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.cert.X509Certificate
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpsTest {
    private val dir = createTempDirectory("organizer-https-test")
    private val file = dir.resolve("https.p12")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun certificate(names: Collection<String>) =
        httpsKeyStore(file, names).getCertificate(HTTPS_KEY_ALIAS) as X509Certificate

    @Test
    fun `makes a certificate once and keeps it while it covers the names`() {
        val first = certificate(listOf("localhost", "127.0.0.1", "192.168.1.20"))
        assertTrue(file.exists())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file))
        assertEquals("SHA256withRSA", first.sigAlgName)
        assertEquals(
            setOf("localhost", "127.0.0.1", "192.168.1.20"),
            first.subjectAlternativeNames.map { it[1] as String }.toSet(),
        )
        assertEquals(first, certificate(listOf("LOCALHOST", "192.168.1.20")), "a name it has already is enough")

        val second = certificate(listOf("localhost", "organizer.lan", "[::1]"))
        assertNotEquals(first, second, "a new name needs a new certificate")
        assertEquals(second, certificate(listOf("::1", "organizer.lan")), "IPv6 addresses match however they're written")
    }

    @Test
    fun `stays within the validity Apple devices accept`() {
        val certificate = certificate(listOf("localhost"))
        val days = (certificate.notAfter.time - certificate.notBefore.time) / 86_400_000
        assertTrue(days in 31..825, "valid for $days days")
    }

    @Test
    fun `a damaged file is replaced`() {
        file.toFile().writeText("not a key store")
        certificate(listOf("localhost"))
    }
}
