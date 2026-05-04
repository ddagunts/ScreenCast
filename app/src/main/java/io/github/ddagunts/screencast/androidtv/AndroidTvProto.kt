package io.github.ddagunts.screencast.androidtv

import java.io.ByteArrayOutputStream

// Hand-rolled proto3 codec for the Android TV Remote v2 schemas.
//
// Field numbers and wire types mirror the .proto definitions under
// app/src/main/proto/. We only encode/decode the fields actually exchanged
// with the TV; unknown fields on the read side are skipped so additive
// schema changes from Google don't break our decode path. This mirrors the
// pattern the Cast V2 envelope uses (cast/CastMessage.kt) — we picked
// hand-rolling here too because protobuf-gradle-plugin and Wire both
// currently fail to apply against AGP 9's built-in Kotlin support.
//
// One file by design: the codecs are small, share a varint+stringField
// helper set, and parallel a single .proto file per port — splitting them
// would scatter helpers without buying anything.
//
// proto3 semantics: zero/empty values are "default" and omitted from the
// wire. Decoders therefore must initialise every field with its default
// before parsing. Enum values that aren't in our switch are decoded as the
// enum's UNKNOWN variant (value 0) — matches the Google protoc-generated
// behaviour we'd otherwise get from javalite.

// ---------- polo OuterMessage (port 6467 envelope; also wraps every
// pairing inner message) ----------

enum class OuterStatus(val wire: Int) {
    UNKNOWN(0), OK(200), ERROR(400), BAD_CONFIGURATION(401), BAD_SECRET(402);
    companion object {
        fun fromWire(v: Int): OuterStatus = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

enum class OuterType(val wire: Int) {
    UNKNOWN(0),
    PAIRING_REQUEST(10), PAIRING_REQUEST_ACK(11),
    OPTIONS(20),
    CONFIGURATION(30), CONFIGURATION_ACK(31),
    SECRET(40), SECRET_ACK(41);
    companion object {
        fun fromWire(v: Int): OuterType = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

// Polo wire envelope: protocol version + status + type discriminator +
// one of the typed inner messages embedded at the field number that
// matches its type code. There is **no** generic payload-bytes field —
// the TV's decoder dispatches on `type` and reads the field at the
// matching number, so misplaced inner data lands as "wrong field" and
// the TV rejects with status=ERROR.
// protocol_version = 2 for the polo v2 protocol that current Android TV /
// Google TV firmware speaks. Empirically confirmed: TVs reject v1 frames
// with status=ERROR. Older Polo v1 deployments are not in the wild on
// any TV that advertises `_androidtvremote2._tcp`.
data class OuterMessage(
    val protocolVersion: Int = 2,
    val status: OuterStatus = OuterStatus.OK,
    val type: OuterType = OuterType.UNKNOWN,
    val pairingRequest: PairingRequest? = null,
    val pairingRequestAck: PairingRequestAck? = null,
    val options: Options? = null,
    val configuration: Configuration? = null,
    val configurationAck: ConfigurationAck? = null,
    val secret: Secret? = null,
    val secretAck: SecretAck? = null,
) {
    // The TV's responses sometimes omit the `type` field entirely and
    // rely on the receiver inferring it from which inner submessage is
    // populated. (Empirically: a successful PAIRING_REQUEST_ACK arrives
    // with type=UNKNOWN but pairing_request_ack present at field 11.)
    // Use this property in dispatch logic rather than `type` directly.
    val effectiveType: OuterType
        get() = if (type != OuterType.UNKNOWN) type else when {
            pairingRequest != null -> OuterType.PAIRING_REQUEST
            pairingRequestAck != null -> OuterType.PAIRING_REQUEST_ACK
            options != null -> OuterType.OPTIONS
            configuration != null -> OuterType.CONFIGURATION
            configurationAck != null -> OuterType.CONFIGURATION_ACK
            secret != null -> OuterType.SECRET
            secretAck != null -> OuterType.SECRET_ACK
            else -> OuterType.UNKNOWN
        }

    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        if (protocolVersion != 0) buf.writeVarintField(1, protocolVersion.toLong())
        if (status.wire != 0) buf.writeVarintField(2, status.wire.toLong())
        // No field 3 — the canonical polo schema has no `type`
        // discriminator. We keep the local `type` property on this
        // class for our own dispatch (effectiveType) but never
        // serialise it.
        // We only emit messages the sender actually sends. Inbound-only
        // submessages (PAIRING_REQUEST_ACK, SECRET_ACK) are decoded from
        // received frames but never put back on the wire — adding empty
        // encoders just so the data class is symmetric would be dead code.
        pairingRequest?.let { buf.writeBytesField(OuterType.PAIRING_REQUEST.wire, it.encode()) }
        options?.let { buf.writeBytesField(OuterType.OPTIONS.wire, it.encode()) }
        configuration?.let { buf.writeBytesField(OuterType.CONFIGURATION.wire, it.encode()) }
        configurationAck?.let { buf.writeBytesField(OuterType.CONFIGURATION_ACK.wire, it.encode()) }
        secret?.let { buf.writeBytesField(OuterType.SECRET.wire, it.encode()) }
        return buf.toByteArray()
    }

    companion object {
        // Convenience constructors. The canonical polo OuterMessage has
        // no `type` discriminator — the receiver dispatches purely on
        // which submessage field is populated. We leave the local `type`
        // property on the data class for our own dispatch (used by the
        // pairing channel's `expect()`), but never serialise it.
        fun pairingRequest(req: PairingRequest, status: OuterStatus = OuterStatus.OK) =
            OuterMessage(status = status, pairingRequest = req)
        fun options(opts: Options, status: OuterStatus = OuterStatus.OK) =
            OuterMessage(status = status, options = opts)
        fun configuration(cfg: Configuration, status: OuterStatus = OuterStatus.OK) =
            OuterMessage(status = status, configuration = cfg)
        fun secret(sec: Secret, status: OuterStatus = OuterStatus.OK) =
            OuterMessage(status = status, secret = sec)

        fun decode(bytes: ByteArray): OuterMessage {
            var protocolVersion = 0
            var status = OuterStatus.UNKNOWN
            var type = OuterType.UNKNOWN
            var pairingRequest: PairingRequest? = null
            var pairingRequestAck: PairingRequestAck? = null
            var options: Options? = null
            var configuration: Configuration? = null
            var configurationAck: ConfigurationAck? = null
            var secret: Secret? = null
            var secretAck: SecretAck? = null
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); protocolVersion = v.toInt(); n }
                    field == 2 && wire == 0 -> { val (v, n) = readVarint(src, off); status = OuterStatus.fromWire(v.toInt()); n }
                    field == 3 && wire == 0 -> { val (v, n) = readVarint(src, off); type = OuterType.fromWire(v.toInt()); n }
                    field == OuterType.PAIRING_REQUEST.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); pairingRequest = PairingRequest.decode(b); n
                    }
                    field == OuterType.PAIRING_REQUEST_ACK.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); pairingRequestAck = PairingRequestAck.decode(b); n
                    }
                    field == OuterType.OPTIONS.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); options = Options.decode(b); n
                    }
                    field == OuterType.CONFIGURATION.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); configuration = Configuration.decode(b); n
                    }
                    field == OuterType.CONFIGURATION_ACK.wire && wire == 2 -> {
                        // ConfigurationAck has no fields — present means present.
                        val (_, n) = readBytes(src, off); configurationAck = ConfigurationAck(); n
                    }
                    field == OuterType.SECRET.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); secret = Secret.decode(b); n
                    }
                    field == OuterType.SECRET_ACK.wire && wire == 2 -> {
                        val (b, n) = readBytes(src, off); secretAck = SecretAck.decode(b); n
                    }
                    else -> skipField(src, off, wire)
                }
            }
            return OuterMessage(
                protocolVersion, status, type,
                pairingRequest, pairingRequestAck, options, configuration,
                configurationAck, secret, secretAck,
            )
        }
    }
}

// ---------- polo pairing inner messages (carried in OuterMessage.payload) ----------

data class PairingRequest(val serviceName: String, val clientName: String) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        if (serviceName.isNotEmpty()) writeStringField(1, serviceName)
        if (clientName.isNotEmpty()) writeStringField(2, clientName)
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): PairingRequest {
            var serviceName = ""; var clientName = ""
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (s, n) = readString(src, off); serviceName = s; n }
                    field == 2 && wire == 2 -> { val (s, n) = readString(src, off); clientName = s; n }
                    else -> skipField(src, off, wire)
                }
            }
            return PairingRequest(serviceName, clientName)
        }
    }
}

data class PairingRequestAck(val serverName: String) {
    companion object {
        fun decode(bytes: ByteArray): PairingRequestAck {
            var serverName = ""
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (s, n) = readString(src, off); serverName = s; n }
                    else -> skipField(src, off, wire)
                }
            }
            return PairingRequestAck(serverName)
        }
    }
}

enum class EncodingType(val wire: Int) {
    UNKNOWN(0), ALPHANUMERIC(1), NUMERIC(2), HEXADECIMAL(3), QRCODE(4);
    companion object {
        fun fromWire(v: Int): EncodingType = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

enum class RoleType(val wire: Int) {
    UNKNOWN(0), INPUT(1), OUTPUT(2);
    companion object {
        fun fromWire(v: Int): RoleType = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

data class Encoding(val type: EncodingType, val symbolLength: Int) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        if (type.wire != 0) writeVarintField(1, type.wire.toLong())
        if (symbolLength != 0) writeVarintField(2, symbolLength.toLong())
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Encoding {
            var type = EncodingType.UNKNOWN; var symbolLength = 0
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); type = EncodingType.fromWire(v.toInt()); n }
                    field == 2 && wire == 0 -> { val (v, n) = readVarint(src, off); symbolLength = v.toInt(); n }
                    else -> skipField(src, off, wire)
                }
            }
            return Encoding(type, symbolLength)
        }
    }
}

data class Options(
    val inputEncodings: List<Encoding>,
    val outputEncodings: List<Encoding>,
    val preferredRole: RoleType,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        for (e in inputEncodings) writeBytesField(1, e.encode())
        for (e in outputEncodings) writeBytesField(2, e.encode())
        if (preferredRole.wire != 0) writeVarintField(3, preferredRole.wire.toLong())
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Options {
            val inputs = mutableListOf<Encoding>()
            val outputs = mutableListOf<Encoding>()
            var role = RoleType.UNKNOWN
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (b, n) = readBytes(src, off); inputs += Encoding.decode(b); n }
                    field == 2 && wire == 2 -> { val (b, n) = readBytes(src, off); outputs += Encoding.decode(b); n }
                    field == 3 && wire == 0 -> { val (v, n) = readVarint(src, off); role = RoleType.fromWire(v.toInt()); n }
                    else -> skipField(src, off, wire)
                }
            }
            return Options(inputs, outputs, role)
        }
    }
}

data class Configuration(val encoding: Encoding, val clientRole: RoleType) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        writeBytesField(1, encoding.encode())
        if (clientRole.wire != 0) writeVarintField(2, clientRole.wire.toLong())
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Configuration {
            var encoding = Encoding(EncodingType.UNKNOWN, 0)
            var role = RoleType.UNKNOWN
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (b, n) = readBytes(src, off); encoding = Encoding.decode(b); n }
                    field == 2 && wire == 0 -> { val (v, n) = readVarint(src, off); role = RoleType.fromWire(v.toInt()); n }
                    else -> skipField(src, off, wire)
                }
            }
            return Configuration(encoding, role)
        }
    }
}

class ConfigurationAck { fun encode(): ByteArray = EMPTY_BYTES }

data class Secret(val secret: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        if (secret.isNotEmpty()) writeBytesField(1, secret)
    }.toByteArray()

    override fun equals(other: Any?) = this === other ||
        (other is Secret && secret.contentEquals(other.secret))
    override fun hashCode() = secret.contentHashCode()

    companion object {
        fun decode(bytes: ByteArray): Secret {
            var s = EMPTY_BYTES
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (b, n) = readBytes(src, off); s = b; n }
                    else -> skipField(src, off, wire)
                }
            }
            return Secret(s)
        }
    }
}

data class SecretAck(val secret: ByteArray) {
    companion object {
        fun decode(bytes: ByteArray): SecretAck {
            var s = EMPTY_BYTES
            forEachField(bytes) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (b, n) = readBytes(src, off); s = b; n }
                    else -> skipField(src, off, wire)
                }
            }
            return SecretAck(s)
        }
    }

    override fun equals(other: Any?) = this === other ||
        (other is SecretAck && secret.contentEquals(other.secret))
    override fun hashCode() = secret.contentHashCode()
}

// ---------- remote service messages (port 6466) ----------
//
// The schema uses a single envelope `RemoteMessage` with optional
// submessages. The TV pings, we reply with pong; we send key injects, the
// TV pushes volume/start/error events. We model the envelope as a sealed
// class for type-safe dispatch in the read loop.

enum class RemoteKeyCode(val wire: Int) {
    UNKNOWN(0),
    SOFT_LEFT(1), SOFT_RIGHT(2), HOME(3), BACK(4),
    DPAD_UP(19), DPAD_DOWN(20), DPAD_LEFT(21), DPAD_RIGHT(22), DPAD_CENTER(23),
    VOLUME_UP(24), VOLUME_DOWN(25), POWER(26),
    MENU(82), NOTIFICATION(83), SEARCH(84),
    MEDIA_PLAY_PAUSE(85), MEDIA_STOP(86), MEDIA_NEXT(87), MEDIA_PREVIOUS(88),
    MEDIA_REWIND(89), MEDIA_FAST_FORWARD(90),
    MUTE(91),
    PAGE_UP(92), PAGE_DOWN(93),
    VOLUME_MUTE(164), INFO(165),
    CHANNEL_UP(166), CHANNEL_DOWN(167),
    GUIDE(172), BOOKMARK(174), CAPTIONS(175),
    SETTINGS(176), TV_POWER(177), TV_INPUT(178),
    MEDIA_AUDIO_TRACK(222),
    SLEEP(223), WAKEUP(224), PAIRING(225), MEDIA_TOP_MENU(226);

    companion object {
        fun fromWire(v: Int): RemoteKeyCode = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

enum class RemoteDirection(val wire: Int) {
    UNKNOWN(0), START_LONG(1), END_LONG(2), SHORT(3);
    companion object {
        fun fromWire(v: Int): RemoteDirection = values().firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

// Canonical RemoteDeviceInfo per tronikos schema. Fields 3 and 4 are
// "unknown" placeholders Sony's BRAVIA firmware fills with `1` and `"12"`
// respectively. Field 5 is the sender's package name; field 6 is its
// version. There is **no** locale field — earlier versions of this code
// added one and it just landed in the TV's unknown-field bin.
data class RemoteDeviceInfo(
    val model: String,
    val vendor: String,
    val unknown1: Int = 1,
    val unknown2: String = "1",
    val packageName: String,
    val appVersion: String,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        if (model.isNotEmpty()) writeStringField(1, model)
        if (vendor.isNotEmpty()) writeStringField(2, vendor)
        if (unknown1 != 0) writeVarintField(3, unknown1.toLong())
        if (unknown2.isNotEmpty()) writeStringField(4, unknown2)
        if (packageName.isNotEmpty()) writeStringField(5, packageName)
        if (appVersion.isNotEmpty()) writeStringField(6, appVersion)
    }.toByteArray()
}

// Sealed envelope. Each variant maps to the field number it occupies in
// the on-wire RemoteMessage. The decoder picks the first populated field
// and ignores everything else.
sealed class RemoteMessage {
    abstract fun encode(): ByteArray

    data class Configure(val code1: Int, val deviceInfo: RemoteDeviceInfo) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (code1 != 0) writeVarintField(1, code1.toLong())
                writeBytesField(2, deviceInfo.encode())
            }.toByteArray()
            return wrap(FIELD_CONFIGURE, inner)
        }
    }

    data class SetActive(val active: Int) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (active != 0) writeVarintField(1, active.toLong())
            }.toByteArray()
            return wrap(FIELD_SET_ACTIVE, inner)
        }
    }

    data class PingRequest(val val1: Int) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (val1 != 0) writeVarintField(1, val1.toLong())
            }.toByteArray()
            return wrap(FIELD_PING_REQUEST, inner)
        }
    }

    data class PingResponse(val val1: Int) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (val1 != 0) writeVarintField(1, val1.toLong())
            }.toByteArray()
            return wrap(FIELD_PING_RESPONSE, inner)
        }
    }

    data class KeyInject(val keyCode: RemoteKeyCode, val direction: RemoteDirection) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (keyCode.wire != 0) writeVarintField(1, keyCode.wire.toLong())
                if (direction.wire != 0) writeVarintField(2, direction.wire.toLong())
            }.toByteArray()
            return wrap(FIELD_KEY_INJECT, inner)
        }
    }

    // Canonical schema: player_model at field 3, volume_max at field 6,
    // volume_level at field 7, volume_muted at field 8. Fields 1,2,4,5
    // are reserved/unknown — we leave them off when sending.
    data class SetVolumeLevel(
        val playerModel: String,
        val volumeLevel: Int,
        val volumeMax: Int,
        val volumeMuted: Boolean,
    ) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (playerModel.isNotEmpty()) writeStringField(3, playerModel)
                if (volumeMax != 0) writeVarintField(6, volumeMax.toLong())
                if (volumeLevel != 0) writeVarintField(7, volumeLevel.toLong())
                if (volumeMuted) writeVarintField(8, 1L)
            }.toByteArray()
            return wrap(FIELD_SET_VOLUME_LEVEL, inner)
        }
    }

    data class AdjustVolumeLevel(val amount: Int) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (amount != 0) writeVarintField(1, amount.toLong())
            }.toByteArray()
            return wrap(FIELD_ADJUST_VOLUME_LEVEL, inner)
        }
    }

    data class AppLinkLaunchRequest(val appLink: String) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (appLink.isNotEmpty()) writeStringField(1, appLink)
            }.toByteArray()
            return wrap(FIELD_APP_LINK_LAUNCH, inner)
        }
    }

    data class StartedNotification(val started: Boolean) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (started) writeVarintField(1, 1L)
            }.toByteArray()
            return wrap(FIELD_START, inner)
        }
    }

    data class Error(val message: String) : RemoteMessage() {
        override fun encode(): ByteArray {
            val inner = ByteArrayOutputStream().apply {
                if (message.isNotEmpty()) writeStringField(1, message)
            }.toByteArray()
            return wrap(FIELD_ERROR, inner)
        }
    }

    // Catch-all for fields we recognise on the wire but don't care about
    // (RemoteImeKeyInject, RemoteImeBatchEdit, plus any future additions).
    // Surfacing this as a discrete variant lets the read loop ignore it
    // without throwing while still logging that something happened.
    data class Unknown(val fieldNumber: Int) : RemoteMessage() {
        override fun encode(): ByteArray = error("Unknown is read-only")
    }

    companion object {
        // Field numbers in the RemoteMessage envelope, taken from the
        // canonical tronikos/androidtvremote2 .proto schema. We had these
        // wrong on first pass — the TV silently rejected (or replied with
        // RemoteError at field 3) every send that landed at the wrong
        // field number. Notably:
        //   * field 3 is RemoteError, NOT a generic ACK envelope.
        //   * fields 8/9 are ping_request/ping_response, NOT volume.
        //   * fields 50/51 are volume, NOT 8/9.
        //   * key_inject is at 10, not 7.
        const val FIELD_CONFIGURE = 1
        const val FIELD_SET_ACTIVE = 2
        const val FIELD_ERROR = 3
        const val FIELD_PING_REQUEST = 8
        const val FIELD_PING_RESPONSE = 9
        const val FIELD_KEY_INJECT = 10
        const val FIELD_IME_KEY_INJECT = 20
        const val FIELD_START = 40
        const val FIELD_SET_VOLUME_LEVEL = 50
        const val FIELD_ADJUST_VOLUME_LEVEL = 51
        const val FIELD_APP_LINK_LAUNCH = 90

        private fun wrap(field: Int, inner: ByteArray): ByteArray =
            ByteArrayOutputStream().apply { writeBytesField(field, inner) }.toByteArray()

        // Decode the envelope: read the first set submessage field and
        // dispatch on its number. Anything else (including the IME pushes
        // we deliberately don't model) returns Unknown(field).
        fun decode(bytes: ByteArray): RemoteMessage {
            var result: RemoteMessage = Unknown(0)
            forEachField(bytes) { field, wire, src, off ->
                if (wire != 2) return@forEachField skipField(src, off, wire)
                val (inner, next) = readBytes(src, off)
                if (result is Unknown) {
                    result = when (field) {
                        FIELD_CONFIGURE -> decodeConfigure(inner)
                        FIELD_SET_ACTIVE -> decodeSetActive(inner)
                        FIELD_PING_REQUEST -> decodePingRequest(inner)
                        FIELD_PING_RESPONSE -> decodePingResponse(inner)
                        FIELD_KEY_INJECT -> decodeKeyInject(inner)
                        FIELD_SET_VOLUME_LEVEL -> decodeSetVolumeLevel(inner)
                        FIELD_ADJUST_VOLUME_LEVEL -> decodeAdjustVolumeLevel(inner)
                        FIELD_APP_LINK_LAUNCH -> decodeAppLink(inner)
                        FIELD_START -> decodeStart(inner)
                        FIELD_ERROR -> decodeError(inner)
                        else -> Unknown(field)
                    }
                }
                next
            }
            return result
        }

        private fun decodeConfigure(b: ByteArray): Configure {
            var code1 = 0
            var info = RemoteDeviceInfo("", "", 0, "", "", "")
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); code1 = v.toInt(); n }
                    field == 2 && wire == 2 -> { val (bb, n) = readBytes(src, off); info = decodeDeviceInfo(bb); n }
                    else -> skipField(src, off, wire)
                }
            }
            return Configure(code1, info)
        }

        private fun decodeDeviceInfo(b: ByteArray): RemoteDeviceInfo {
            var model = ""; var vendor = ""; var unknown1 = 0
            var unknown2 = ""; var pkg = ""; var ver = ""
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (s, n) = readString(src, off); model = s; n }
                    field == 2 && wire == 2 -> { val (s, n) = readString(src, off); vendor = s; n }
                    field == 3 && wire == 0 -> { val (v, n) = readVarint(src, off); unknown1 = v.toInt(); n }
                    field == 4 && wire == 2 -> { val (s, n) = readString(src, off); unknown2 = s; n }
                    field == 5 && wire == 2 -> { val (s, n) = readString(src, off); pkg = s; n }
                    field == 6 && wire == 2 -> { val (s, n) = readString(src, off); ver = s; n }
                    else -> skipField(src, off, wire)
                }
            }
            return RemoteDeviceInfo(model, vendor, unknown1, unknown2, pkg, ver)
        }

        private fun decodeSetActive(b: ByteArray): SetActive {
            var active = 0
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); active = v.toInt(); n }
                    else -> skipField(src, off, wire)
                }
            }
            return SetActive(active)
        }

        private fun decodePingRequest(b: ByteArray): PingRequest {
            var v1 = 0
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); v1 = v.toInt(); n }
                    else -> skipField(src, off, wire)
                }
            }
            return PingRequest(v1)
        }

        private fun decodePingResponse(b: ByteArray): PingResponse {
            var v1 = 0
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); v1 = v.toInt(); n }
                    else -> skipField(src, off, wire)
                }
            }
            return PingResponse(v1)
        }

        private fun decodeKeyInject(b: ByteArray): KeyInject {
            var code = RemoteKeyCode.UNKNOWN; var dir = RemoteDirection.UNKNOWN
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); code = RemoteKeyCode.fromWire(v.toInt()); n }
                    field == 2 && wire == 0 -> { val (v, n) = readVarint(src, off); dir = RemoteDirection.fromWire(v.toInt()); n }
                    else -> skipField(src, off, wire)
                }
            }
            return KeyInject(code, dir)
        }

        private fun decodeSetVolumeLevel(b: ByteArray): SetVolumeLevel {
            // Canonical fields: player_model=3, volume_max=6,
            // volume_level=7, volume_muted=8. Fields 1/2/4/5 are unknown
            // and we just skip them.
            var pm = ""; var lvl = 0; var max = 0; var muted = false
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 3 && wire == 2 -> { val (s, n) = readString(src, off); pm = s; n }
                    field == 6 && wire == 0 -> { val (v, n) = readVarint(src, off); max = v.toInt(); n }
                    field == 7 && wire == 0 -> { val (v, n) = readVarint(src, off); lvl = v.toInt(); n }
                    field == 8 && wire == 0 -> { val (v, n) = readVarint(src, off); muted = v != 0L; n }
                    else -> skipField(src, off, wire)
                }
            }
            return SetVolumeLevel(pm, lvl, max, muted)
        }

        private fun decodeAdjustVolumeLevel(b: ByteArray): AdjustVolumeLevel {
            var amt = 0
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); amt = v.toInt(); n }
                    else -> skipField(src, off, wire)
                }
            }
            return AdjustVolumeLevel(amt)
        }

        private fun decodeAppLink(b: ByteArray): AppLinkLaunchRequest {
            var link = ""
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (s, n) = readString(src, off); link = s; n }
                    else -> skipField(src, off, wire)
                }
            }
            return AppLinkLaunchRequest(link)
        }

        private fun decodeStart(b: ByteArray): StartedNotification {
            var started = false
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 0 -> { val (v, n) = readVarint(src, off); started = v != 0L; n }
                    else -> skipField(src, off, wire)
                }
            }
            return StartedNotification(started)
        }

        private fun decodeError(b: ByteArray): Error {
            var msg = ""
            forEachField(b) { field, wire, src, off ->
                when {
                    field == 1 && wire == 2 -> { val (s, n) = readString(src, off); msg = s; n }
                    else -> skipField(src, off, wire)
                }
            }
            return Error(msg)
        }
    }
}

// ---------- shared codec primitives ----------

private val EMPTY_BYTES = ByteArray(0)

private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
    writeRawVarint(((field shl 3) or 0).toLong())
    writeRawVarint(value)
}

private fun ByteArrayOutputStream.writeStringField(field: Int, s: String) {
    writeBytesField(field, s.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeBytesField(field: Int, value: ByteArray) {
    writeRawVarint(((field shl 3) or 2).toLong())
    writeRawVarint(value.size.toLong())
    write(value)
}

private fun ByteArrayOutputStream.writeRawVarint(value: Long) {
    var v = value
    while (v and 0x7FL.inv() != 0L) {
        write(((v and 0x7FL) or 0x80L).toInt())
        v = v ushr 7
    }
    write(v.toInt() and 0x7F)
}

// Iterates every (field, wireType) pair in a serialised proto3 message.
// `step` is responsible for advancing the offset past the field's value
// — `forEachField` only owns the tag-reading. Returning the new offset
// keeps the per-call read logic colocated with the field's wire type.
private inline fun forEachField(bytes: ByteArray, step: (field: Int, wire: Int, src: ByteArray, off: Int) -> Int) {
    var offset = 0
    while (offset < bytes.size) {
        val (tag, next) = readVarint(bytes, offset)
        offset = next
        val field = (tag ushr 3).toInt()
        val wire = (tag and 7).toInt()
        offset = step(field, wire, bytes, offset)
    }
}

// Skip a single field of the given wire type. Returns the new offset. The
// caller is responsible for not skipping types it has separately consumed.
private fun skipField(bytes: ByteArray, start: Int, wire: Int): Int = when (wire) {
    0 -> readVarint(bytes, start).second
    1 -> { require(start + 8 <= bytes.size) { "fixed64 past end" }; start + 8 }
    5 -> { require(start + 4 <= bytes.size) { "fixed32 past end" }; start + 4 }
    2 -> {
        val (len, next) = readVarint(bytes, start)
        require(len >= 0 && next + len <= bytes.size) { "len-delimited past end ($len)" }
        next + len.toInt()
    }
    else -> error("unsupported wire type $wire to skip")
}

private fun readString(bytes: ByteArray, start: Int): Pair<String, Int> {
    val (b, next) = readBytes(bytes, start)
    return String(b, Charsets.UTF_8) to next
}

private fun readBytes(bytes: ByteArray, start: Int): Pair<ByteArray, Int> {
    val (len, next) = readVarint(bytes, start)
    require(len >= 0 && next + len <= bytes.size) { "bytes past end ($len)" }
    val out = ByteArray(len.toInt())
    System.arraycopy(bytes, next, out, 0, out.size)
    return out to (next + out.size)
}

private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
    var result = 0L; var shift = 0; var i = start
    val limit = minOf(bytes.size, start + 10)
    while (i < limit) {
        val b = bytes[i].toInt() and 0xFF; i++
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result to i
        shift += 7
    }
    error("malformed varint at offset $start")
}
