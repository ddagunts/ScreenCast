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
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

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
    private val config: WebRtcSessionConfig,
    private val onIceCandidate: (IceCandidate) -> Unit,
) {
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var audioCapture: WebRtcAudioCapture? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

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
        // Restrict candidate gathering to real Wi-Fi / Ethernet. Without this
        // libwebrtc also offers VPN-tunnel (10.x), CGN (192.0.0.x), and public
        // IPv6 candidates — none of which a LAN Chromecast can reach. The ICE
        // check then fails on the top-priority pair and times out in 15 s
        // before falling through to the real Wi-Fi candidate (symptom: session
        // stuck on "Signaling" on the Android side, ICE state FAILED in logs).
        val pcfOptions = PeerConnectionFactory.Options().apply {
            networkIgnoreMask =
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK or
                    PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                    PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
        }

        // Build the factory. When audio is enabled we install our custom ADM
        // (JavaAudioDeviceModule + AudioBufferCallback) so MediaProjection PCM
        // can be fed into libwebrtc's audio pipeline — the AudioRecord itself
        // is attached later in addScreenSource() once MediaProjection is
        // available. When audio is disabled we skip the ADM entirely, no
        // m=audio section appears in the OFFER, and there's nothing to
        // configure — cheapest way to honor the user's audio toggle.
        val factoryBuilder = PeerConnectionFactory.builder()
            .setOptions(pcfOptions)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))

        if (config.audioEnabled) {
            val capture = WebRtcAudioCapture()
            audioCapture = capture
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setSampleRate(WEBRTC_AUDIO_SAMPLE_RATE)
                .setUseStereoInput(WEBRTC_AUDIO_CHANNELS == 2)
                // HW AEC + NS are VoIP mic algorithms that mangle playback
                // capture (music gets noise-gated, echoes get "cancelled"
                // against a reference signal that doesn't exist).
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioBufferCallback(capture)
                .createAudioDeviceModule()
            audioModule = adm
            factoryBuilder.setAudioDeviceModule(adm)
        }

        factory = factoryBuilder.createPeerConnectionFactory()

        // Empty ICE server list: both peers are on the same LAN, so host
        // candidates alone are reachable. STUN would add latency for no gain;
        // TURN is pointless without Internet-routed servers we'd have to run.
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // continualGatheringPolicy defaults to GATHER_ONCE, which is fine
            // for a LAN — no network changes expected mid-cast.
            //
            // Prefer the Wi-Fi adapter when libwebrtc chooses between available
            // networks. Complementary to WebRtcCastSession.bindProcessToWifi()
            // — the bind forces socket-level routing, this preference guides
            // candidate selection priority.
            networkPreference = PeerConnection.AdapterType.WIFI
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
        val preset = config.videoPreset
        cap.startCapture(preset.width, preset.height, preset.fps)

        val track = f.createVideoTrack("screen0", source)
        videoTrack = track
        // addTrack with an empty streamIds list emits an a=msid:- line; the
        // receiver page treats the incoming transceiver as its primary video
        // stream, so the streamId string itself is cosmetic.
        // Separate stream IDs for video and audio so the receiver can give each
        // its own MediaStream. When both share one stream, the receiver's
        // MediaStream dispatches tracks in arrival order and the audio element
        // can end up sharing a processing queue with large video frames — a
        // contributor to choppy audio on the TV.
        val sender = pc.addTrack(track, listOf("screen-video"))
        configureVideoSender(sender)

        if (config.audioEnabled) {
            // Audio track. ScreenCapturerAndroid owns the MediaProjection
            // instance (obtained lazily inside startCapture); share it with
            // AudioPlaybackCapture so we don't need a second consent prompt.
            // If the projection isn't exposed yet (older AAR), the audio
            // callback streams silence — the track still exists, just empty.
            val projection = cap.mediaProjection
            if (projection != null) {
                audioCapture?.attachProjection(projection)
            } else {
                logW("ScreenCapturerAndroid.getMediaProjection returned null — audio will be silent")
            }
            // Audio constraints: disable libwebrtc's audio-processing chain for
            // our non-voice source. googEchoCancellation / googAutoGainControl
            // / googNoiseSuppression / googHighpassFilter are VoIP-microphone
            // features; leaving them on mangles music playback capture.
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            val aSource = f.createAudioSource(audioConstraints)
            audioSource = aSource
            val aTrack = f.createAudioTrack("audio0", aSource)
            audioTrack = aTrack
            pc.addTrack(aTrack, listOf("screen-audio"))
        }
    }

    // Override libwebrtc's conservative defaults for a LAN-only cast:
    //  • maxBitrateBps — without this the BWE settles around 1–2 Mbps even on a
    //    gigabit Wi-Fi. Giving it an explicit ceiling lets the encoder spend
    //    the bandwidth we actually have.
    //  • degradationPreference = MAINTAIN_RESOLUTION — BALANCED (the default)
    //    will drop resolution under CPU pressure, which looks terrible for
    //    screen content (text smears). We'd rather drop frames and keep
    //    pixels sharp. Frame drops are far less noticeable on UI/video casts
    //    than resolution shifts.
    private fun configureVideoSender(sender: org.webrtc.RtpSender) {
        val params = sender.parameters
        if (params.encodings.isEmpty()) {
            logW("configureVideoSender: no encodings on sender, skipping bitrate/degradation tuning")
            return
        }
        val maxBitrate = config.maxBitrateBps
        params.encodings[0].maxBitrateBps = maxBitrate
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        // setParameters() returns false when libwebrtc rejects the update
        // (e.g. if a renegotiation clobbered it). Log it — the defaults will
        // still work, just conservatively.
        val applied = sender.setParameters(params)
        if (applied) {
            logI("video sender configured: preset=${config.videoPreset.label} maxBitrate=${maxBitrate / 1_000_000} Mbps degradation=MAINTAIN_RESOLUTION")
        } else {
            logW("video sender.setParameters returned false — bitrate/degradation not applied")
        }
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
        runCatching { audioTrack?.dispose() }.onFailure { logW("close: audioTrack.dispose: $it") }
        runCatching { audioSource?.dispose() }.onFailure { logW("close: audioSource.dispose: $it") }
        // audioCapture.release() stops the AudioRecord. Order matters: stop
        // the AudioRecord before disposing audioModule, otherwise its
        // callback thread can still be mid-read when the module goes away.
        runCatching { audioCapture?.release() }.onFailure { logW("close: audioCapture.release: $it") }
        runCatching { audioModule?.release() }.onFailure { logW("close: audioModule.release: $it") }
        runCatching { peer?.close() }.onFailure { logW("close: peer.close: $it") }
        runCatching { peer?.dispose() }.onFailure { logW("close: peer.dispose: $it") }
        runCatching { factory?.dispose() }.onFailure { logW("close: factory.dispose: $it") }
        runCatching { eglBase.release() }.onFailure { logW("close: eglBase.release: $it") }
        capturer = null; videoTrack = null; videoSource = null
        surfaceTextureHelper = null; peer = null; factory = null
        audioTrack = null; audioSource = null
        audioCapture = null; audioModule = null
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
