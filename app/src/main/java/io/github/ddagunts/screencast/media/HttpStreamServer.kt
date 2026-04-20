package io.github.ddagunts.screencast.media

import io.github.ddagunts.screencast.util.logI
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

class HttpStreamServer(
    private val port: Int,
    private val segmenter: HlsSegmenter,
    private val token: String,
) {
    private var engine: CIOApplicationEngine? = null

    fun start() {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(AutoHeadResponse)
            install(CORS) {
                // Token in the URL path is the real auth gate; CORS stays wide because
                // hls.js inside the Default Media Receiver fetches from a google.com
                // origin that we don't want to hardcode.
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Head)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.Range)
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Accept)
                exposeHeader(HttpHeaders.ContentLength)
                exposeHeader(HttpHeaders.ContentRange)
                exposeHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/health") {
                    call.respondText("OK")
                }
                get("/c/{token}/stream.m3u8") {
                    if (!authorized(call.parameters["token"])) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
                    logI("GET stream.m3u8 (master) from ${call.request.local.remoteHost}")
                    call.respondText(MASTER_PLAYLIST, ContentType("application", "x-mpegURL"))
                }
                get("/c/{token}/media.m3u8") {
                    if (!authorized(call.parameters["token"])) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
                    val pl = segmenter.playlist()
                    logI("GET media.m3u8 from ${call.request.local.remoteHost}")
                    call.respondText(pl, ContentType("application", "x-mpegURL"))
                }
                get("/c/{token}/seg-{seq}.ts") {
                    if (!authorized(call.parameters["token"])) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
                    val seq = call.parameters["seq"]?.toIntOrNull()
                    val bytes = seq?.let { segmenter.getSegment(it) }
                    logI("GET seg-$seq.ts from ${call.request.local.remoteHost} -> ${if (bytes == null) "404" else "${bytes.size}B"}")
                    if (bytes == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                    else call.respondBytes(bytes, ContentType("video", "mp2t"))
                }
            }
        }.also { it.start(wait = false) }
        logI("HTTP server listening on :$port (token-gated)")
    }

    // Constant-time comparison to avoid leaking token via timing on the LAN.
    private fun authorized(supplied: String?): Boolean {
        if (supplied == null) return false
        val a = supplied.toByteArray(Charsets.UTF_8)
        val b = token.toByteArray(Charsets.UTF_8)
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    companion object {
        private val MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3500000,CODECS="mp4a.40.2,avc1.42E01F",RESOLUTION=1280x720,NAME="720"
            media.m3u8
        """.trimIndent() + "\n"
    }
}
