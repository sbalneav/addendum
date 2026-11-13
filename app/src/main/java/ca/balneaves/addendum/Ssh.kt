package ca.balneaves.addendum

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Result of running one remote command. */
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** The server presented a host key we haven't pinned yet. The UI asks the user to trust it. */
class UnknownHostKeyException(val hostId: String, val keyBase64: String, val fingerprint: String) :
    Exception("Unknown host key for $hostId: $fingerprint")

/** The server's host key differs from the pinned one. Never auto-accepted. */
class ChangedHostKeyException(hostId: String, fingerprint: String) :
    Exception("THE HOST KEY FOR $hostId HAS CHANGED (now $fingerprint). Refusing to connect.")

/** OpenSSH-style fingerprint: "SHA256:" + unpadded base64 of SHA-256(key blob). */
fun fingerprint(keyBlob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
    return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
}

fun fingerprintOf(keyBase64: String): String = fingerprint(Base64.decode(keyBase64, Base64.NO_WRAP))

/**
 * The phone's SSH identity: an ECDSA P-256 key pair generated on first use and kept
 * in the app's private storage. One key for every profile.
 *
 * ECDSA rather than Ed25519 because Android's built-in crypto (which JSch uses) supports
 * EC everywhere, while Ed25519 would need BouncyCastle bundled in. The server's
 * authorized_keys entry pins this key to the addendum forced command.
 */
class Identity(context: Context) {
    private val dir = context.filesDir
    private val prv = File(dir, "id_ecdsa")
    private val pub = File(dir, "id_ecdsa.pub")

    @Synchronized
    fun ensure() {
        if (prv.exists() && pub.exists()) return
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val p = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val q = ByteArrayOutputStream().also { kp.writePublicKey(it, "addendum-phone") }.toByteArray()
        kp.dispose()
        prv.writeBytes(p)
        pub.writeBytes(q)
    }

    /** One line, suitable for authorized_keys. */
    fun publicKeyLine(): String {
        ensure()
        return pub.readText().trim()
    }

    fun addTo(jsch: JSch) {
        ensure()
        jsch.addIdentity("addendum", prv.readBytes(), pub.readBytes(), null)
    }

    /** Throw away the key pair; a new one is generated on next use. */
    fun regenerate() {
        prv.delete()
        pub.delete()
        ensure()
    }
}

/**
 * Trust-on-first-use host key store holding exactly one pinned key for the host
 * being connected to. JSch calls [check] during the handshake; we remember what it
 * saw so [SshClient] can turn JSch's generic failure into a specific exception.
 */
private class PinnedHostKeyRepo(private val pinned: String?) : HostKeyRepository {
    var seenKey: ByteArray? = null
    var result: Int = HostKeyRepository.OK

    override fun check(host: String, key: ByteArray): Int {
        seenKey = key
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        result = when {
            pinned == null -> HostKeyRepository.NOT_INCLUDED
            pinned == b64 -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
        return result
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "addendum-pinned"

    override fun getHostKey(): Array<HostKey> = pinnedKeys("")
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = pinnedKeys(host ?: "")

    // Reporting the pinned key lets JSch prefer the same host key algorithm every time.
    private fun pinnedKeys(host: String): Array<HostKey> = try {
        pinned?.let { arrayOf(HostKey(host, Base64.decode(it, Base64.NO_WRAP))) } ?: emptyArray()
    } catch (e: JSchException) {
        emptyArray()
    }
}

/** Where to connect. [hostId] keys the pinned host key, so one server's key covers all its profiles. */
data class Endpoint(val host: String, val port: Int, val user: String) {
    val hostId: String get() = if (port == 22) host else "$host:$port"
}

class SshClient(private val endpoint: Endpoint, private val pinnedHostKey: String?, private val identity: Identity) {

    /**
     * Run [command] on the server, feeding [stdin] to it, and wait for it to finish.
     * With a forced command on the server, [command] arrives there as $SSH_ORIGINAL_COMMAND.
     * Blocking: call from a background dispatcher.
     */
    fun run(command: String, stdin: String = ""): CommandResult {
        require(endpoint.host.isNotBlank()) { "This profile has no server set." }
        require(endpoint.user.isNotBlank()) { "This profile has no username set." }
        val jsch = JSch()
        identity.addTo(jsch)
        val repo = PinnedHostKeyRepo(pinnedHostKey)
        jsch.setHostKeyRepository(repo)

        val session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.timeout = 15_000
        try {
            try {
                session.connect(10_000)
            } catch (e: JSchException) {
                val key = repo.seenKey
                if (key != null && repo.result == HostKeyRepository.NOT_INCLUDED)
                    throw UnknownHostKeyException(endpoint.hostId, Base64.encodeToString(key, Base64.NO_WRAP), fingerprint(key))
                if (key != null && repo.result == HostKeyRepository.CHANGED)
                    throw ChangedHostKeyException(endpoint.hostId, fingerprint(key))
                throw e
            }

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
            val err = ByteArrayOutputStream()
            channel.setErrStream(err)
            val out = channel.inputStream
            channel.connect(10_000)
            val stdout = out.readBytes().toString(Charsets.UTF_8)
            while (!channel.isClosed) Thread.sleep(20)
            val exit = channel.exitStatus
            channel.disconnect()
            return CommandResult(exit, stdout, err.toString(Charsets.UTF_8.name()))
        } finally {
            session.disconnect()
        }
    }
}
