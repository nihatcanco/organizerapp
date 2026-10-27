package organizer.store

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Encrypts secret settings, such as the Anthropic API key, before they go into the database.
 *
 * Its key is made from two parts: 32 random bytes in [keyFile], made on first use and readable only by the app's
 * user, and this computer's [machineId]. A backup of the database can't be decrypted without the key file, and a
 * copy of the whole data folder can't be decrypted on another computer. Without a machine ID (in Docker, unless
 * ORGANIZER_MACHINE_ID is set) the key file alone protects it.
 *
 * Values are encrypted with AES-256-GCM, each with its own random nonce. The setting's name is authenticated along
 * with it, so a sealed value doesn't open as another setting.
 */
class SecretBox(private val keyFile: Path, private val machineId: String?) {
    private val random = SecureRandom()

    val tiedToMachine: Boolean get() = machineId != null

    fun seal(name: String, value: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val sealed = cipher(Cipher.ENCRYPT_MODE, key(create = true)!!, nonce, name).doFinal(value.toByteArray())
        return VERSION + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    /** The value [seal] was given, or null when it can't be decrypted here: on another computer, or without the key file. */
    fun open(name: String, sealed: String): String? {
        if (!sealed.startsWith(VERSION)) return null
        val key = key(create = false) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(sealed.removePrefix(VERSION)) }.getOrNull() ?: return null
        if (bytes.size <= NONCE_BYTES) return null
        return try {
            String(cipher(Cipher.DECRYPT_MODE, key, bytes.copyOf(NONCE_BYTES), name).doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
        } catch (e: AEADBadTagException) {
            null
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray, name: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(name.toByteArray())
        }

    /** The key file's bytes mixed with the machine's ID; null when there's no key file yet and [create] is false. */
    @Synchronized
    private fun key(create: Boolean): SecretKeySpec? {
        val secret = readKeyFile() ?: if (create) createKeyFile() else return null
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return SecretKeySpec(mac.doFinal("organizer settings\u0000${machineId.orEmpty()}".toByteArray()), "AES")
    }

    private fun readKeyFile(): ByteArray? {
        if (!keyFile.exists()) return null
        return keyFile.readBytes().also {
            check(it.size == KEY_BYTES) { "$keyFile is damaged. Delete it and enter the API key again in Settings." }
        }
    }

    private fun createKeyFile(): ByteArray {
        val secret = ByteArray(KEY_BYTES).also(random::nextBytes)
        keyFile.toAbsolutePath().parent?.createDirectories()
        // Written next to it first, so a crash never leaves half a key behind.
        val partial = keyFile.resolveSibling("${keyFile.fileName}.part")
        partial.deleteIfExists()
        if ("posix" in keyFile.fileSystem.supportedFileAttributeViews()) {
            val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.createFile(partial, PosixFilePermissions.asFileAttribute(ownerOnly))
        } else {
            Files.createFile(partial)
        }
        Files.write(partial, secret)
        Files.move(partial, keyFile, StandardCopyOption.ATOMIC_MOVE)
        log.info("Made a new key for encrypting settings in {}", keyFile)
        return secret
    }

    companion object {
        private val log = LoggerFactory.getLogger(SecretBox::class.java)
        private const val VERSION = "v1:"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * This computer's ID, which stays the same until the operating system is reinstalled: MachineGuid on Windows,
 * IOPlatformUUID on macOS and /etc/machine-id on Linux. Null when there is none, as inside Docker, whose containers
 * can't see the computer they run on; there, Jenkins passes the host's ID as ORGANIZER_MACHINE_ID.
 */
fun findMachineId(): String? {
    val os = System.getProperty("os.name").lowercase()
    val id = when {
        // /reg:64, as a 32-bit Java would otherwise be shown a copy of the registry that lacks it.
        "win" in os -> command("reg", "query", """HKLM\SOFTWARE\Microsoft\Cryptography""", "/v", "MachineGuid", "/reg:64")
            ?.let { Regex("""MachineGuid\s+REG_SZ\s+(\S+)""").find(it)?.groupValues?.get(1) }
        "mac" in os -> command("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            ?.let { Regex(""""IOPlatformUUID"\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        else -> listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
            .firstNotNullOfOrNull { path -> runCatching { Path.of(path).readText() }.getOrNull() }
    }
    return id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

private fun command(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
}.getOrNull()
