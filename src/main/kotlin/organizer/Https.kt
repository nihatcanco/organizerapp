package organizer

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

const val HTTPS_KEY_ALIAS = "organizer"

/**
 * Not a secret: the key store sits next to secret.key, readable only by whoever can read the database anyway.
 * Java just won't keep a key without one.
 */
const val HTTPS_KEY_STORE_PASSWORD = "organizer"

// Apple devices refuse certificates valid for longer than 825 days, even ones the user trusts.
private const val VALID_DAYS = 800L
private val RENEW_BEFORE = Duration.ofDays(30)

/**
 * The key store with the certificate for HTTPS, kept in [file]. It's self-signed, so browsers warn about it until
 * the user accepts it once. Made on first use and made again when it's about to expire or doesn't cover all of
 * [names] (host names and IP addresses), e.g. because ORGANIZER_PUBLIC_URL changed. Keeping the same certificate
 * otherwise matters: browsers remember that the user accepted this one.
 */
fun httpsKeyStore(file: Path, names: Collection<String>): KeyStore {
    val wanted = names.map(::normalized).toSet()
    if (file.exists()) {
        val existing = runCatching {
            KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, HTTPS_KEY_STORE_PASSWORD.toCharArray()) } }
        }.getOrNull()
        val certificate = existing?.getCertificate(HTTPS_KEY_ALIAS) as? X509Certificate
        if (certificate != null && certificate.notAfter.toInstant() > Instant.now() + RENEW_BEFORE &&
            certificateNames(certificate).containsAll(wanted)
        ) {
            return existing
        }
    }
    val (ipAddresses, domains) = wanted.partition { isIpAddress(it) }
    val keyStore = buildKeyStore {
        certificate(HTTPS_KEY_ALIAS) {
            password = HTTPS_KEY_STORE_PASSWORD
            hash = HashAlgorithm.SHA256
            keySizeInBits = 2048
            daysValid = VALID_DAYS
            this.domains = domains
            this.ipAddresses = ipAddresses.map { InetAddress.getByName(it) }
        }
    }
    save(keyStore, file)
    LoggerFactory.getLogger("organizer").info("Made a new HTTPS certificate for ${wanted.sorted().joinToString(", ")}.")
    return keyStore
}

/** Saves [keyStore] readable only by this app's user, like secret.key, since it holds the certificate's private key. */
private fun save(keyStore: KeyStore, file: Path) {
    file.toAbsolutePath().parent?.createDirectories()
    // Written next to it first, so a crash never leaves half a file behind.
    val partial = file.resolveSibling("${file.fileName}.part")
    partial.deleteIfExists()
    if ("posix" in file.fileSystem.supportedFileAttributeViews()) {
        val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.createFile(partial, PosixFilePermissions.asFileAttribute(ownerOnly))
    } else {
        Files.createFile(partial)
    }
    partial.outputStream().use { keyStore.store(it, HTTPS_KEY_STORE_PASSWORD.toCharArray()) }
    Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** The host names and IP addresses [certificate] is valid for. */
private fun certificateNames(certificate: X509Certificate): Set<String> =
    certificate.subjectAlternativeNames.orEmpty().mapNotNull { (it[1] as? String)?.let(::normalized) }.toSet()

/** Lowercase, and IP addresses written the way Java writes them, since "::1" and "0:0:0:0:0:0:0:1" are the same. */
private fun normalized(name: String): String {
    val plain = name.lowercase().removePrefix("[").removeSuffix("]")
    return if (isIpAddress(plain)) InetAddress.getByName(plain).hostAddress else plain
}

// Only literal addresses, so this never looks a name up.
private fun isIpAddress(name: String) = name.matches(Regex("""[\d.]+""")) || ':' in name
