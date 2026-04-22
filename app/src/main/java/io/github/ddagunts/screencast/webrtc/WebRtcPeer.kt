package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import io.github.ddagunts.screencast.util.logE
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

// One-shot wrapper around libwebrtc's PeerConnectionFactory + PeerConnection.
// Lifecycle mirrors the cast session: build() once, addScreenSource() once,
// createOffer() → setLocalDescription() → network exchange → setRemoteDescription(answer)
// → addIceCandidate* → close(). Safe to call close() repeatedly.
//
// Threading: libwebrtc callbacks arrive on internal worker threads. We only
// expose them via StateFlows (connectionState, iceCandidates), so the Kotlin
// coroutine side is responsible for its own thread discipline.
class WebRtcPeer(
    private val context: Context,
    private val onIceCandidate: (IceCandidate) -> Unit,
) {
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState

    // Factory init is a process-wide singleton call inside libwebrtc — guard
    // against callers doing it multiple times in a single app lifetime. The
    // static init is cheap after the first call, but the AAR's native code
    // asserts on re-init of certain subsystems on some ABIs.
    fun build() {
        synchronized(WebRtcPeer::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // Empty ICE server list: both peers are on the same LAN, so host
        // candidates alone are reachable. STUN would add latency for no gain;
        // TURN is pointless without Internet-routed servers we'd have to run.
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // continualGatheringPolicy defaults to GATHER_ONCE, which is fine
            // for a LAN — no network changes expected mid-cast.
        }

        peer = factory!!.createPeerConnection(rtcConfig, observer)
            ?: error("PeerConnectionFactory.createPeerConnection returned null")
    }

    // Attach a screen-source track. Uses libwebrtc's ScreenCapturerAndroid which
    // wraps MediaProjection internally. The MediaProjection callback onStop is
    // forwarded via the `onProjectionStopped` lambda so the caller can tear
    // the whole session down when the user revokes consent from the notification.
    //
    // On Android 14+, MediaProjection must be obtained via Intent within a
    // foreground service that's already started with the mediaProjection FGS
    // type — ScreenCapturerAndroid.initialize() does that internally using
    // the result data we pass through.
    fun addScreenSource(
        projectionData: Intent,
        onProjectionStopped: () -> Unit,
    ) {
        val pc = peer ?: error("addScreenSource: build() first")
        val f = factory ?: error("addScreenSource: build() first")

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                logI("MediaProjection stopped — tearing WebRTC peer down")
                onProjectionStopped()
            }
        }
        val cap = ScreenCapturerAndroid(projectionData, projectionCallback)
        capturer = cap

        val source = f.createVideoSource(cap.isScreencast)
        videoSource = source

        val helper = SurfaceTextureHelper.create("WebRtcScreenCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper
        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(WEBRTC_VIDEO_WIDTH, WEBRTC_VIDEO_HEIGHT, WEBRTC_VIDEO_FPS)

        val track = f.createVideoTrack("screen0", source)
        videoTrack = track
        // addTrack with an empty streamIds list emits an a=msid:- line; the
        // receiver page treats the incoming transceiver as its primary video
        // stream, so the streamId string itself is cosmetic.
        pc.addTrack(track, listOf("screen-stream"))
    }

    suspend fun createOffer(): String {
        val pc = peer ?: error("createOffer: build() first")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) { offerDeferred.complete(desc) }
            override fun onCreateFailure(err: String?) {
                offerDeferred.completeExceptionally(RuntimeException("createOffer failed: $err"))
            }
        }, constraints)
        val offer = offerDeferred.await()

        val setDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { setDeferred.complete(Unit) }
            override fun onSetFailure(err: String?) {
                setDeferred.completeExceptionally(RuntimeException("setLocalDescription failed: $err"))
            }
        }, offer)
        setDeferred.await()
        return offer.description
    }

    suspend fun setRemoteAnswer(sdp: String) {
        val pc = peer ?: error("setRemoteAnswer: build() first")
        val done = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(err: String?) {
                done.completeExceptionally(RuntimeException("setRemoteDescription failed: $err"))
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        done.await()
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peer?.addIceCandidate(candidate)
            ?: logW("addRemoteIceCandidate: peer is null (late message?)")
    }

    fun close() {
        runCatching { capturer?.stopCapture() }.onFailure { logW("close: stopCapture: $it") }
        runCatching { capturer?.dispose() }.onFailure { logW("close: capturer.dispose: $it") }
        runCatching { videoTrack?.dispose() }.onFailure { logW("close: videoTrack.dispose: $it") }
        runCatching { videoSource?.dispose() }.onFailure { logW("close: videoSource.dispose: $it") }
        runCatching { surfaceTextureHelper?.dispose() }.onFailure { logW("close: helper.dispose: $it") }
        runCatching { peer?.close() }.onFailure { logW("close: peer.close: $it") }
        runCatching { peer?.dispose() }.onFailure { logW("close: peer.dispose: $it") }
        runCatching { factory?.dispose() }.onFailure { logW("close: factory.dispose: $it") }
        runCatching { eglBase.release() }.onFailure { logW("close: eglBase.release: $it") }
        capturer = null; videoTrack = null; videoSource = null
        surfaceTextureHelper = null; peer = null; factory = null
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            logI("signaling state: $state")
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            logI("ice state: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            logI("ice gathering: $state")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            onIceCandidate.invoke(candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {
            logI("renegotiation needed")
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            logI("peer connection state: $newState")
            _connectionState.value = newState
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                logE("peer connection FAILED — ICE could not establish")
            }
        }
    }

    // SDP observer default-empty base. Kotlin doesn't allow us to implement
    // just two of four methods; this saves the boilerplate at each call site.
    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }

    companion object {
        @Volatile
        private var factoryInitialized = false
    }
}
