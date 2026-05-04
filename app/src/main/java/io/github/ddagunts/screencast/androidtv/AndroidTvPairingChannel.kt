package io.github.ddagunts.screencast.androidtv

import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

// Drives the polo pairing handshake on TLS port 6467.
//
// Public surface is a single suspending `pair()` that returns the server
// cert SHA-256 fingerprint on success — the caller (typically
// AndroidTvCertStore via AndroidTvRemote) persists it. Failures bubble
// out as Result.failure with one of the typed PairingError codes so the
// UI can render distinct messages instead of a single "pairing failed".
//
// The flow is strictly sequential: every send is followed by an expected
// receive, and we abort on the first surprise. We do not run a parallel
// read loop here — there's no async traffic on this port until the user
// types the code, and even then the TV only replies, never pushes.
class AndroidTvPairingChannel(
    private val host: String,
    private val pairingPort: Int = 6467,
    private val clientMaterial: AndroidTvCertFactory.Material,
    private val clientName: String = "ScreenCast",
    private val serviceName: String = "androidtvremote2",
) {

    sealed class PairingError(message: String) : Exception(message) {
        class Unreachable(host: String, cause: Throwable?) :
            PairingError("Could not connect to $host:6467 (is 'Remote control via Wi-Fi' enabled on the TV?)") {
            init { initCause(cause) }
        }
        class HandshakeFailed(message: String, cause: Throwable? = null) :
            PairingError(message) { init { if (cause != null) initCause(cause) } }
        class ProtocolViolation(message: String) : PairingError(message)
        class CodeRejected(status: Int) : PairingError("TV rejected the pairing code (status $status)")
        class Cancelled : PairingError("pairing cancelled")
    }

    data class Result(val serverCertSha256: String)

    suspend fun pair(onCodePrompt: suspend () -> String?): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        runCatching { runPairing(onCodePrompt) }
    }

    private suspend fun runPairing(onCodePrompt: suspend () -> String?): Result {
        logI("=== PAIRING START to $host:$pairingPort (clientName=$clientName, serviceName=$serviceName) ===")
        val socketFactory = AndroidTvSocketFactory(clientMaterial, expectedServerCertSha256 = null)
        val socket = try {
            socketFactory.connect(host, pairingPort)
        } catch (e: IOException) {
            throw PairingError.Unreachable(host, e)
        }

        try {
            // The pairing socket is short-lived; failure-mode is "throw &
            // close" because there's no continuation to recover into.
            val serverCert = socket.session.peerCertificates.first() as X509Certificate
            val fp = sha256Hex(serverCert.encoded)
            logI("pairing server cert SHA-256 = $fp")
            logI("client cert subject = ${clientMaterial.cert.subjectX500Principal}")
            logI("client public exponent = ${(clientMaterial.cert.publicKey as java.security.interfaces.RSAPublicKey).publicExponent}")

            // 1. PAIRING_REQUEST → wait for PAIRING_REQUEST_ACK.
            sendOuter(socket, OuterMessage.pairingRequest(
                PairingRequest(serviceName, clientName)
            ))
            expect(socket, OuterType.PAIRING_REQUEST_ACK)

            // 2. OPTIONS exchange. We always advertise hexadecimal/6 in
            //    both directions; the TV echoes a compatible Options back.
            //    Some firmwares fail to populate the echoed Options — we
            //    only care that the type matches and status is OK.
            sendOuter(socket, OuterMessage.options(Options(
                inputEncodings = listOf(Encoding(EncodingType.HEXADECIMAL, 6)),
                outputEncodings = listOf(Encoding(EncodingType.HEXADECIMAL, 6)),
                preferredRole = RoleType.INPUT,
            )))
            expect(socket, OuterType.OPTIONS)

            // 3. CONFIGURATION → wait for CONFIGURATION_ACK. The TV
            //    displays its 6-digit code as soon as it sends the ACK.
            sendOuter(socket, OuterMessage.configuration(Configuration(
                encoding = Encoding(EncodingType.HEXADECIMAL, 6),
                clientRole = RoleType.INPUT,
            )))
            expect(socket, OuterType.CONFIGURATION_ACK)

            // 4. Suspend until the UI returns the typed code (null = user
            //    cancelled). We don't impose a timeout here — the TV
            //    keeps the code on screen indefinitely.
            val code = onCodePrompt() ?: throw PairingError.Cancelled()

            // 5. Compute SECRET. Hash mismatches in the SECRET_ACK below
            //    flag a wrong code (most common) or an algorithm bug
            //    (which the AndroidTvPairingHash tests should have caught).
            val hash = AndroidTvPairingHash.compute(
                clientPublic = clientMaterial.cert.publicKey as java.security.interfaces.RSAPublicKey,
                serverPublic = serverCert.publicKey as java.security.interfaces.RSAPublicKey,
                code = code,
            )
            sendOuter(socket, OuterMessage.secret(Secret(hash)))

            val ack = expect(socket, OuterType.SECRET_ACK)
            if (ack.status != OuterStatus.OK) throw PairingError.CodeRejected(ack.status.wire)

            // Defense in depth: cross-check the server's hash matches
            // ours. Modulo TV firmware bugs this should always hold; if
            // it doesn't, the wire-level pairing succeeded but our hash
            // computation diverged from the TV's — fail closed.
            val serverHash = ack.secretAck?.secret ?: ByteArray(0)
            if (!serverHash.contentEquals(hash)) {
                logE("server SECRET_ACK hash mismatch — wire-level pair OK but hash diverged")
                throw PairingError.HandshakeFailed("server returned a different SECRET hash")
            }

            logI("pairing complete with $host (server cert pinned at $fp)")
            return Result(serverCertSha256 = fp)
        } finally {
            runCatching { socket.close() }
        }
    }

    // Read the next OuterMessage and fail loudly if it's not the type we
    // were expecting. The handshake is rigidly ordered, so a wrong type
    // is always a protocol violation rather than a recoverable race.
    private fun expect(socket: SSLSocket, type: OuterType, timeoutMs: Int = 10_000): OuterMessage {
        logI("--- AWAIT $type (timeout ${timeoutMs}ms) ---")
        socket.soTimeout = timeoutMs
        val frame = try {
            AndroidTvFraming.readFrame(socket.inputStream)
        } catch (e: IOException) {
            throw PairingError.HandshakeFailed("read failed waiting for $type", e)
        }
        // Schema-less dump first — if our OuterMessage decoder is wrong,
        // this still shows ground truth.
        logI("<< structure:\n${AndroidTvProtoDump.dump(frame)}")
        val msg = OuterMessage.decode(frame)
        logI("<< decoded: protocolVersion=${msg.protocolVersion} status=${msg.status}(${msg.status.wire}) type=${msg.type}(${msg.type.wire}) effectiveType=${msg.effectiveType}")
        logI("<< inner: pairingRequestAck=${msg.pairingRequestAck} options=${msg.options} configurationAck=${msg.configurationAck != null} secretAck=${msg.secretAck?.let { "(${it.secret.size}B)" }}")
        if (msg.effectiveType != type) {
            throw PairingError.ProtocolViolation(
                "expected $type, got ${msg.effectiveType} (status ${msg.status}, type=${msg.type})"
            )
        }
        if (msg.status != OuterStatus.OK && msg.status != OuterStatus.UNKNOWN) {
            // OK and UNKNOWN both pass — UNKNOWN happens when the TV
            // doesn't populate the field on echoed messages, which is fine.
            logW("$type carried non-OK status: ${msg.status}")
        }
        return msg
    }

    private fun sendOuter(socket: SSLSocket, msg: OuterMessage) {
        val bytes = msg.encode()
        logI("--- SEND ${msg.type} (${bytes.size}B) ---")
        logI(">> structure:\n${AndroidTvProtoDump.dump(bytes)}")
        try {
            AndroidTvFraming.writeFrame(socket.outputStream, bytes)
        } catch (e: IOException) {
            throw PairingError.HandshakeFailed("write failed sending ${msg.type}", e)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(d.size * 2) {
            for (b in d) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4]); append(HEX[v and 0x0F])
            }
        }
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

