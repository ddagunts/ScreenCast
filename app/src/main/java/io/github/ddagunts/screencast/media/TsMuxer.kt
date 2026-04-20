package io.github.ddagunts.screencast.media

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG-TS muxer for a single H.264 video elementary stream.
 * 188-byte packets, PAT on PID 0x0000, PMT on PID 0x1000, video on PID 0x0100.
 */
class TsMuxer(
    val audioSampleRate: Int = 44100,
    val audioChannelCount: Int = 2,
) {
    private val buf = ByteArrayOutputStream(64 * 1024)
    private var videoCC = 0
    private var audioCC = 0
    private var pmtCC = 0
    private var patCC = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val samplingIndex = SAMPLING_FREQUENCIES.indexOf(audioSampleRate).also {
        require(it >= 0) { "unsupported audio sample rate $audioSampleRate" }
    }

    fun setSpsPps(sps: ByteArray, pps: ByteArray) { this.sps = sps; this.pps = pps }

    fun writeTablesAndKeyframe(nals: List<ByteArray>, ptsUs: Long) {
        writePat(); writePmt()
        writeVideoPes(nalsWithParameters(nals), ptsUs, true)
    }

    fun writeFrame(nals: List<ByteArray>, ptsUs: Long, isKey: Boolean) {
        if (isKey) { writePat(); writePmt() }
        val payload = if (isKey) nalsWithParameters(nals) else nalsWithAud(nals)
        writeVideoPes(payload, ptsUs, isKey)
    }

    fun writeAudioFrame(aacData: ByteArray, ptsUs: Long) {
        writeAudioPes(wrapAdts(aacData), ptsUs)
    }

    fun drainTo(): ByteArray { val b = buf.toByteArray(); buf.reset(); return b }

    private fun wrapAdts(aac: ByteArray): ByteArray {
        val frameLength = 7 + aac.size
        val profileBits = 1                                 // AAC-LC = 2 → profile field = profile-1 = 1
        val channelConfig = audioChannelCount
        val out = ByteArray(frameLength)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()                              // MPEG-4, no CRC
        out[2] = (((profileBits and 0x3) shl 6) or ((samplingIndex and 0xF) shl 2) or ((channelConfig shr 2) and 0x1)).toByte()
        out[3] = (((channelConfig and 0x3) shl 6) or ((frameLength shr 11) and 0x3)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()                              // buffer_fullness=all1, 0 blocks
        System.arraycopy(aac, 0, out, 7, aac.size)
        return out
    }

    private fun nalsWithParameters(nals: List<ByteArray>): List<ByteArray> {
        val out = ArrayList<ByteArray>(nals.size + 3)
        out.add(AUD_NAL)
        sps?.let { out.add(it) }
        pps?.let { out.add(it) }
        nals.forEach { if (!isAud(it) && !isSpsOrPps(it)) out.add(it) }
        return out
    }

    private fun nalsWithAud(nals: List<ByteArray>): List<ByteArray> {
        val out = ArrayList<ByteArray>(nals.size + 1)
        out.add(AUD_NAL)
        nals.forEach { if (!isAud(it)) out.add(it) }
        return out
    }

    private fun isAud(n: ByteArray) = n.isNotEmpty() && (n[0].toInt() and 0x1F) == 9
    private fun isSpsOrPps(n: ByteArray): Boolean {
        if (n.isEmpty()) return false
        val t = n[0].toInt() and 0x1F; return t == 7 || t == 8
    }

    private fun writePat() {
        val section = ByteArrayOutputStream().apply {
            write(0x00)                                    // table_id = PAT
            writeShort(0xB000 or (13 and 0x0FFF))          // section_syntax=1, section_length=13
            writeShort(0x0001)                             // transport_stream_id
            write(0xC1)                                    // version=0, current_next=1
            write(0x00); write(0x00)                       // section#, last_section#
            writeShort(0x0001)                             // program_number=1
            writeShort(0xE000 or PMT_PID)                  // reserved | PMT PID
        }.toByteArray()
        val withCrc = appendCrc32(section)
        writeTsSection(PAT_PID, withCrc, patCC).also { patCC = (patCC + 1) and 0x0F }
    }

    private fun writePmt() {
        // Two elementary streams: H.264 video on VIDEO_PID + AAC-ADTS audio on AUDIO_PID.
        // Per-stream descriptor block: stream_type(1) + reserved|PID(2) + reserved|ES_info_length(2) = 5 bytes.
        // Section body fixed portion: 2+1+1+1+2+2 = 9 bytes, + 2*5 streams = 10, + 4 CRC = 23 bytes.
        val sectionLength = 23
        val section = ByteArrayOutputStream().apply {
            write(0x02)
            writeShort(0xB000 or (sectionLength and 0x0FFF))
            writeShort(0x0001)
            write(0xC1)
            write(0x00); write(0x00)
            writeShort(0xE000 or VIDEO_PID)                // PCR_PID = video
            writeShort(0xF000)                             // program_info_length=0
            write(0x1B); writeShort(0xE000 or VIDEO_PID); writeShort(0xF000)     // H.264
            write(0x0F); writeShort(0xE000 or AUDIO_PID); writeShort(0xF000)     // AAC ADTS
        }.toByteArray()
        val withCrc = appendCrc32(section)
        writeTsSection(PMT_PID, withCrc, pmtCC).also { pmtCC = (pmtCC + 1) and 0x0F }
    }

    private fun writeTsSection(pid: Int, section: ByteArray, cc: Int) {
        val packet = ByteArray(188).also { it.fill(0xFF.toByte()) }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()   // payload_unit_start=1
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or (cc and 0x0F)).toByte()            // payload only
        packet[4] = 0x00                                        // pointer_field
        val capacity = 188 - 5
        val len = minOf(section.size, capacity)
        System.arraycopy(section, 0, packet, 5, len)
        buf.write(packet)
    }

    private fun writeVideoPes(nals: List<ByteArray>, ptsUs: Long, randomAccess: Boolean) {
        val payload = ByteArrayOutputStream()
        for (nal in nals) {
            payload.write(0x00); payload.write(0x00); payload.write(0x00); payload.write(0x01)
            payload.write(nal)
        }
        writePes(VIDEO_PID, 0xE0, payload.toByteArray(), ptsUs, randomAccess, ccGetter = { videoCC },
            ccSetter = { videoCC = it })
    }

    private fun writeAudioPes(adts: ByteArray, ptsUs: Long) {
        writePes(AUDIO_PID, 0xC0, adts, ptsUs, randomAccess = true, ccGetter = { audioCC },
            ccSetter = { audioCC = it })
    }

    private fun writePes(
        pid: Int, streamId: Int, esData: ByteArray, ptsUs: Long, randomAccess: Boolean,
        ccGetter: () -> Int, ccSetter: (Int) -> Unit,
    ) {
        // MPEG-TS uses a 90 kHz clock for PTS/PCR: µs × 90_000 / 1_000_000 = µs × 9 / 100.
        val pts90k = (ptsUs * 9) / 100

        val pes = ByteArrayOutputStream()
        pes.write(0x00); pes.write(0x00); pes.write(0x01)
        pes.write(streamId)
        val pesLengthPlaceholder = esData.size + 8           // 8 = PES header (3 flag bytes + 5 byte PTS)
        if (pid == VIDEO_PID) pes.writeShort(0)              // video: unbounded
        else pes.writeShort(pesLengthPlaceholder)
        pes.write(0x80)
        pes.write(0x80)
        pes.write(0x05)
        writePts(pes, pts90k, 0x02)
        pes.write(esData)
        val pesBytes = pes.toByteArray()

        val includePcrFirst = (pid == VIDEO_PID)             // video PID carries the PCR
        var off = 0; var first = true
        while (off < pesBytes.size) {
            val packet = ByteArray(188).also { it.fill(0xFF.toByte()) }
            packet[0] = 0x47
            packet[1] = (((if (first) 0x40 else 0x00)) or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()

            val remaining = pesBytes.size - off
            val wantsAf = (first && randomAccess) || (first && includePcrFirst) || (remaining < 184)

            val afFlags: Int
            val afBytes: ByteArray
            if (wantsAf) {
                val af = ByteArrayOutputStream()
                var flags = 0
                if (first && randomAccess) flags = flags or 0x40
                if (first && includePcrFirst) flags = flags or 0x10
                af.write(flags)
                if (first && includePcrFirst) writePcr(af, pts90k)
                if (remaining < 184 - af.size()) {
                    val stuff = 184 - af.size() - 1 - remaining
                    for (i in 0 until stuff) af.write(0xFF)
                }
                afBytes = af.toByteArray()
                afFlags = 0x30
            } else {
                afFlags = 0x10
                afBytes = ByteArray(0)
            }

            val cc = ccGetter()
            packet[3] = (afFlags or (cc and 0x0F)).toByte()
            ccSetter((cc + 1) and 0x0F)
            var p = 4
            if (afBytes.isNotEmpty() || afFlags == 0x30) {
                packet[p++] = afBytes.size.toByte()
                System.arraycopy(afBytes, 0, packet, p, afBytes.size); p += afBytes.size
            }
            val payloadSpace = 188 - p
            val take = minOf(payloadSpace, remaining)
            System.arraycopy(pesBytes, off, packet, p, take)
            off += take; first = false
            buf.write(packet)
        }
    }

    private fun writePts(out: ByteArrayOutputStream, pts90k: Long, prefix: Int) {
        val p = pts90k and 0x1FFFFFFFFL
        val b0 = ((prefix shl 4) or (((p shr 30).toInt() and 0x07) shl 1) or 0x01) and 0xFF
        val b1 = ((p shr 22).toInt() and 0xFF)
        val b2 = ((((p shr 15).toInt() and 0x7F) shl 1) or 0x01) and 0xFF
        val b3 = ((p shr 7).toInt() and 0xFF)
        val b4 = (((p.toInt() and 0x7F) shl 1) or 0x01) and 0xFF
        out.write(b0); out.write(b1); out.write(b2); out.write(b3); out.write(b4)
    }

    private fun writePcr(out: ByteArrayOutputStream, pts90k: Long) {
        val base = pts90k and 0x1FFFFFFFFL
        val ext = 0L
        val b0 = ((base shr 25).toInt() and 0xFF)
        val b1 = ((base shr 17).toInt() and 0xFF)
        val b2 = ((base shr 9).toInt() and 0xFF)
        val b3 = ((base shr 1).toInt() and 0xFF)
        val b4 = ((((base.toInt() and 0x01) shl 7)) or 0x7E or ((ext shr 8).toInt() and 0x01)) and 0xFF
        val b5 = (ext.toInt() and 0xFF)
        out.write(b0); out.write(b1); out.write(b2); out.write(b3); out.write(b4); out.write(b5)
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }

    private fun appendCrc32(section: ByteArray): ByteArray {
        val crc = mpegCrc32(section)
        val out = section.copyOf(section.size + 4)
        out[section.size] = ((crc shr 24) and 0xFF).toByte()
        out[section.size + 1] = ((crc shr 16) and 0xFF).toByte()
        out[section.size + 2] = ((crc shr 8) and 0xFF).toByte()
        out[section.size + 3] = (crc and 0xFF).toByte()
        return out
    }

    private fun mpegCrc32(bytes: ByteArray): Int {
        var crc = -0x1
        for (b in bytes) {
            val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor CRC_TABLE[idx]
        }
        return crc
    }

    companion object {
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x0100
        const val AUDIO_PID = 0x0101
        // ADTS sampling frequency table index → Hz
        private val SAMPLING_FREQUENCIES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350
        )
        private val AUD_NAL = byteArrayOf(0x09, 0xF0.toByte())

        private val CRC_TABLE = IntArray(256).also {
            val poly = 0x04C11DB7
            for (i in 0 until 256) {
                var c = i shl 24
                for (k in 0 until 8) c = if ((c and 0x80000000.toInt()) != 0) (c shl 1) xor poly else c shl 1
                it[i] = c
            }
        }
    }
}
