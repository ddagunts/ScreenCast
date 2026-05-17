// ScreenCast WebRTC — Cast custom receiver that consumes a WebRTC video
// stream from the Android sender and renders it fullscreen.
//
// Namespace must match WEBRTC_NAMESPACE in
// app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt.
const NAMESPACE = 'urn:x-cast:io.github.ddagunts.screencast.webrtc';

const video = document.getElementById('video');
const statusEl = document.getElementById('status');

function setStatus(text) {
  if (!statusEl) return;
  statusEl.textContent = text;
}
function hideStatus() {
  if (!statusEl) return;
  statusEl.classList.add('hidden');
}

// Surface any sync JS error and any unhandled promise rejection onto the
// #status element — that's the only feedback channel the TV screen gives us
// without remote debugging. The marker "JS:" distinguishes this from the
// static HTML default text, proving our script actually ran.
window.addEventListener('error', (evt) => {
  setStatus(`JS: ${evt.message} @ ${(evt.filename || '').split('/').pop()}:${evt.lineno}`);
});
window.addEventListener('unhandledrejection', (evt) => {
  const r = evt.reason;
  setStatus(`JS promise: ${r && (r.message || r.toString ? r.toString() : r)}`);
});

// Prove the script loaded at all; CAF init happens right after.
setStatus('receiver.js loaded, starting CAF…');

if (!window.cast || !cast.framework) {
  setStatus('JS: cast.framework missing — CAF SDK did not load');
  throw new Error('cast.framework missing');
}

const context = cast.framework.CastReceiverContext.getInstance();
// Route CAF's internal logs to the browser console. If remote debugging is
// ever enabled on the Chromecast, they'll show up in DevTools.
if (cast.framework.LoggerLevel) {
  context.setLoggerLevel(cast.framework.LoggerLevel.DEBUG);
}
const options = new cast.framework.CastReceiverOptions();
// Two separate timers, both must be tamed for a long-lived WebRTC cast:
//   • disableIdleTimeout — CAF auto-closes the receiver after ~5 min if no
//     media has played through its media pipeline. We bypass CAF media
//     entirely (video/audio go through RTCPeerConnection, not <video> via
//     CAF), so CAF always sees the receiver as idle. Without this flag the
//     cast drops at the 5-minute mark, no matter what maxInactivity is set
//     to. This is the symptom users hit before this line existed.
//   • maxInactivity — the sender heartbeat threshold. CAF will close an
//     idle sender connection after this many seconds. Our Cast V2 channel
//     PINGs every 5 s anyway, so this rarely matters, but a long ceiling
//     keeps us safe if the sender stalls briefly.
options.disableIdleTimeout = true;
options.maxInactivity = 24 * 60 * 60;
// Register our custom namespace. CAF refuses sendCustomMessage calls on a
// namespace it doesn't know about, so this has to come before start().
// Regular `{}` (not Object.create(null)) — some CAF code paths call
// prototype methods on this map.
options.customNamespaces = {};
options.customNamespaces[NAMESPACE] =
  cast.framework.system.MessageType.JSON;

// Track the senderId we're negotiating with so we can reply to ICE and
// ANSWER with an explicit target. Broadcast would also work (the platform
// only has one sender in practice) but targeted replies keep the logs
// sensible if a second sender ever attaches.
let senderId = null;

// Current RTCPeerConnection — rebuilt on every new OFFER. The previous
// implementation made this a module-level `const`, which meant once we
// `pc.close()` on BYE, any subsequent OFFER silently failed (setRemoteDescription
// throws on a closed peer, handled-but-invisible). Recreate per OFFER so each
// cast attempt starts fresh.
let pc = null;

function closeCurrentPeer() {
  if (!pc) return;
  try { pc.close(); } catch (_) {}
  pc = null;
}

function createPeer() {
  // Empty iceServers list: both peers are on the same LAN, so host candidates
  // are enough; no STUN round-trip, no TURN relays.
  const p = new RTCPeerConnection({ iceServers: [] });

  // Both remote tracks (video, audio) land in a single MediaStream wired to
  // the fullscreen <video> element. Two reasons not to split them:
  //   1. The sender uses distinct stream IDs (screen-video / screen-audio),
  //      so each ontrack fires with its own evt.streams[0]. We have to
  //      compose them on this side either way.
  //   2. Routing audio via Web Audio's createMediaStreamSource() is a
  //      long-standing Chrome bug for WebRTC remote tracks — the source
  //      node attaches but produces no output. HTMLMediaElement is the
  //      reliable path; the <video> element already plays the video, so
  //      adding the audio track to the same stream gives us audio for
  //      free, no second element needed.
  // We do NOT set video.muted = true. Chromecast's CAF receiver is a
  // trusted context that permits unmuted autoplay, and Chrome's autoplay
  // policy has been observed to commit to a muted state if set on load,
  // silently reverting later video.muted = false. See the receiver
  // index.html comment for the matching note on the HTML side.
  const remoteStream = new MediaStream();
  video.srcObject = remoteStream;

  p.ontrack = (evt) => {
    const kind = evt.track && evt.track.kind || '?';
    console.log('ontrack', kind);
    remoteStream.addTrack(evt.track);
    setStatus(`track: ${kind} (tracks: ${remoteStream.getTracks().length})`);
    const vp = video.play();
    if (vp && typeof vp.catch === 'function') {
      vp.catch(err => setStatus(`video.play failed: ${err && err.name || err}`));
    }
  };

  p.onicecandidate = (evt) => {
    if (!evt.candidate) return;
    const payload = {
      type: 'ICE',
      candidate: {
        candidate: evt.candidate.candidate,
        sdpMid: evt.candidate.sdpMid,
        sdpMLineIndex: evt.candidate.sdpMLineIndex,
      },
    };
    sendSignal(payload);
  };

  p.onconnectionstatechange = () => {
    const s = p.connectionState;
    console.log('pc state', s);
    if (s === 'connected') hideStatus();
    else if (s === 'failed') setStatus('WebRTC connection failed');
    else if (s === 'disconnected') setStatus('Sender disconnected');
    else setStatus(`pc state: ${s}`);
  };
  p.oniceconnectionstatechange = () => {
    setStatus(`ice: ${p.iceConnectionState}`);
  };

  return p;
}

function sendSignal(obj) {
  // With a known senderId we respond directly; before that (e.g. READY on
  // startup) we broadcast — the CAF framework rejects broadcasts to non-
  // senders with a warning but delivers them to every attached sender.
  try {
    context.sendCustomMessage(NAMESPACE, senderId, obj);
  } catch (e) {
    console.warn('sendCustomMessage', e);
  }
}

context.addCustomMessageListener(NAMESPACE, async (evt) => {
  senderId = evt.senderId || senderId;
  const msg = evt.data;
  if (!msg || typeof msg !== 'object') return;
  try {
    if (msg.type === 'OFFER') {
      // Tear down any previous peer — a closed or half-configured one can't
      // process a new OFFER. This lets the receiver serve back-to-back casts
      // without requiring a page reload.
      closeCurrentPeer();
      pc = createPeer();
      setStatus('OFFER received, setting remote…');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      setStatus('remote set, creating answer…');
      const answer = await pc.createAnswer();
      setStatus('answer created, setting local…');
      await pc.setLocalDescription(answer);
      setStatus('answer set, sending…');
      sendSignal({ type: 'ANSWER', sdp: answer.sdp });
      setStatus('ANSWER sent, awaiting media');
    } else if (msg.type === 'ICE' && msg.candidate) {
      // Trickle candidates — empty candidate string signals end-of-gather
      // from the sender. Safe to pass through addIceCandidate; Chrome ignores
      // an end-of-candidate marker.
      if (!pc) return;
      const c = msg.candidate;
      if (c.candidate) {
        await pc.addIceCandidate({
          candidate: c.candidate,
          sdpMid: c.sdpMid,
          sdpMLineIndex: c.sdpMLineIndex,
        });
      }
    } else if (msg.type === 'BYE') {
      console.log('sender sent BYE');
      setStatus('Sender ended the cast');
      closeCurrentPeer();
    }
  } catch (err) {
    // Surface the failure on-screen. The try/catch used to only log to console,
    // which is invisible on the Chromecast without remote DevTools. When
    // setRemoteDescription or createAnswer throws, the TV now tells us which
    // step failed so we can diagnose from the couch.
    console.error('signal handler', msg && msg.type, err);
    const name = err && (err.name || 'Error');
    const message = err && (err.message || String(err));
    setStatus(`signal ${msg.type} failed: ${name}: ${message}`);
  }
});

setStatus('calling context.start()…');
try {
  context.start(options);
} catch (e) {
  setStatus(`start() threw: ${e && (e.message || e)}`);
  throw e;
}
// "CAF ready" marker distinguishes live JS from the static HTML default.
setStatus('CAF ready — waiting for sender');
// Announce readiness — the sender treats this as a hint but doesn't block
// on it (the OFFER is sent regardless after LAUNCH completes).
sendSignal({ type: 'READY' });
