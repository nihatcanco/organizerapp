package organizer.store

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretBoxTest {
    private val dir = createTempDirectory("organizer-secret-test")
    private val keyFile = dir.resolve("secret.key")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `opens what it sealed, and nothing else`() {
        val box = SecretBox(keyFile, "machine-a")
        assertFalse(keyFile.exists(), "the key file is made when first needed")
        val sealed = box.seal("api_key", "sk-ant-secret")
        assertFalse("sk-ant-secret" in sealed)
        assertNotEquals(sealed, box.seal("api_key", "sk-ant-secret"), "each value gets its own nonce")

        assertEquals("sk-ant-secret", box.open("api_key", sealed))
        assertEquals("sk-ant-secret", SecretBox(keyFile, "machine-a").open("api_key", sealed))
        assertNull(box.open("other_setting", sealed), "a sealed value doesn't open as another setting")
        assertNull(box.open("api_key", sealed.dropLast(4) + "AAAA"), "a changed value doesn't open")
        assertNull(box.open("api_key", "plain text"))
    }

    @Test
    fun `a copy doesn't open on another computer or without the key file`() {
        val sealed = SecretBox(keyFile, "machine-a").seal("api_key", "sk-ant-secret")
        assertNull(SecretBox(keyFile, "machine-b").open("api_key", sealed))
        assertNull(SecretBox(keyFile, null).open("api_key", sealed))

        val otherDir = dir.resolve("restored")
        assertNull(SecretBox(otherDir.resolve("secret.key"), "machine-a").open("api_key", sealed))
        Files.createDirectories(otherDir)
        otherDir.resolve("secret.key").writeBytes(ByteArray(32))
        assertNull(SecretBox(otherDir.resolve("secret.key"), "machine-a").open("api_key", sealed))
    }

    @Test
    fun `only the app's user can read the key file`() {
        SecretBox(keyFile, "machine-a").seal("api_key", "sk-ant-secret")
        assertEquals(32, Files.size(keyFile))
        if ("posix" in keyFile.fileSystem.supportedFileAttributeViews()) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(keyFile))
        }
    }

    @Test
    fun `finds this computer's ID`() {
        // Every computer the tests run on has one, outside Docker.
        val id = findMachineId()
        assertTrue(!id.isNullOrBlank())
        assertEquals(id, findMachineId())
    }
}
