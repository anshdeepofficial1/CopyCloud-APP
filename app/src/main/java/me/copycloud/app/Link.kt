package me.copycloud.app

import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Wire {
    const val MAX = 300_000
    fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun derive(key: ByteArray, challenge: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(challenge + label.toByteArray(Charsets.UTF_8))
        }
    fun seal(key: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return nonce + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(data)
        }
    }
    fun open(key: ByteArray, packet: ByteArray): ByteArray {
        require(packet.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(0, 12)))
            doFinal(packet.copyOfRange(12, packet.size))
        }
    }
    fun read(input: DataInputStream): ByteArray {
        val n = input.readInt()
        require(n in 1..MAX)
        return ByteArray(n).also { input.readFully(it) }
    }
    fun write(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size in 1..MAX)
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
}
data class Clip(val text: String, val source: String, val received: Boolean)
data class LinkState(val status: String = "Not connected", val connected: Boolean = false, val busy: Boolean = false, val peers: Int = 0, val history: List<Clip> = emptyList())
class Link(private val deviceName: String) {
    val state = MutableStateFlow(LinkState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var sendKey: ByteArray? = null
    private var counter = 0L
    private var connection: Job? = null
    private val lock = Any()
    fun connect(invite: String) {
        disconnect()
        state.value = state.value.copy(status = "Connecting…", busy = true)
        connection = scope.launch {
            val current = Socket()
            socket = current
            try {
                val parts = invite.trim().split('|')
                require(parts.size == 3 && parts[0] == "CC1") { "Invalid invite format" }
                val host = parts[1].substringBeforeLast(':')
                val port = parts[1].substringAfterLast(':').toInt()
                val secret = Wire.decode(parts[2])
                require(secret.size == 32 && host.isNotBlank() && port in 1..65535) { "Invalid invite" }
                current.connect(InetSocketAddress(host, port), 5000)
                current.soTimeout = 8000
                val input = DataInputStream(BufferedInputStream(current.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(current.getOutputStream()))
                val challenge = Wire.read(input)
                require(challenge.size == 32)
                val tx = Wire.derive(secret, challenge, "client")
                val rx = Wire.derive(secret, challenge, "server")
                secret.fill(0)
                synchronized(lock) {
                    counter = 0
                    output = out
                    sendKey = tx
                    sendLocked(JSONObject().put("type", "join").put("name", deviceName))
                }
                var received = 0L
                while (isActive) {
                    val msg = JSONObject(String(Wire.open(rx, Wire.read(input)), Charsets.UTF_8))
                    require(msg.getLong("seq") == ++received) { "Invalid message sequence" }
                    when (msg.getString("type")) {
                        "welcome" -> {
                            current.soTimeout = 0
                            state.value = state.value.copy(status = "Connected", connected = true, busy = false)
                        }
                        "peers" -> state.value = state.value.copy(peers = msg.getInt("count"))
                        "clip" -> {
                            val item = Clip(msg.getString("text"), msg.getString("source"), true)
                            state.value = state.value.copy(history = (listOf(item) + state.value.history).take(50))
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive && socket === current) state.value = state.value.copy(status = if (e is IllegalArgumentException) e.message ?: "Invalid invite" else "Connection closed or failed", connected = false, busy = false, peers = 0)
            } finally {
                current.close()
                synchronized(lock) {
                    if (socket === current) { socket = null; output = null; sendKey = null }
                }
            }
        }
    }
    private fun sendLocked(msg: JSONObject) {
        val out = checkNotNull(output)
        val key = checkNotNull(sendKey)
        msg.put("seq", ++counter)
        Wire.write(out, Wire.seal(key, msg.toString().toByteArray(Charsets.UTF_8)))
    }
    fun send(text: String) {
        if (!state.value.connected || text.isBlank() || text.toByteArray().size > 60_000) return
        val target = socket
        scope.launch {
            try {
                synchronized(lock) {
                    check(socket === target)
                    sendLocked(JSONObject().put("type", "clip").put("text", text))
                }
                val item = Clip(text, "This phone", false)
                state.value = state.value.copy(history = (listOf(item) + state.value.history).take(50))
            } catch (_: Exception) {
                if (socket === target) disconnect()
            }
        }
    }
    fun clearHistory() { state.value = state.value.copy(history = emptyList()) }
    fun disconnect() {
        connection?.cancel()
        socket?.close()
        synchronized(lock) { socket = null; output = null; sendKey = null }
        state.value = state.value.copy(status = "Not connected", connected = false, busy = false, peers = 0)
    }
    fun close() { disconnect(); scope.cancel() }
}
