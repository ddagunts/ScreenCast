// ScreenCast WebRTC — Cast custom receiver that consumes a WebRTC video
// stream from the Android sender and renders it fullscreen.
//
// Namespace must match WEBRTC_NAMESPACE in
// app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt.
const NAMESPACE = 'urn:x-cast:io.github.ddagunts.screencast.webrtc';

const video = document.getElementById('video');
const statusEl = document.getElementById('status');

// Dedicated <audio> element for the audio track. Keeps autoplay rules separate
// from the <video> element (which typically needs `muted` to autoplay); an
// <audio> in a CAF receiver context can play unmuted right away. If we tried
// to play audio through <video>, muted fallback would silence us permanently.
const audio = document.createElement('audio');
audio.autoplay = true;
audio.setAttribute('playsinline', '');
document.body.appendChild(audio);

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
// We don't use the CAF media pipeline at all — the sender never sends LOAD,
// so we disable the idle-timeout auto-shutdown. maxInactivity is in seconds;
// 24h is a reasonable "basically forever" value for a live screen cast.
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

  p.ontrack = (evt) => {
    const kind = evt.track && evt.track.kind || '?';
    console.log('ontrack', kind, evt.streams && evt.streams[0]);
    const stream = (evt.streams && evt.streams[0]) || new MediaStream([evt.track]);
    setStatus(`track: ${kind} (tracks in stream: ${stream.getTracks().length})`);
    if (kind === 'audio') {
      // Route audio to the dedicated <audio> element. autoplay on an <audio>
      // in a CAF receiver is permitted unmuted; playing audio through a
      // muted-autoplay <video> would silence us.
      audio.srcObject = stream;
      audio.muted = false;
      audio.volume = 1.0;
      const ap = audio.play();
      if (ap && typeof ap.catch === 'function') {
        ap.catch(err => setStatus(`audio.play failed: ${err && err.name || err}`));
      }
    } else {
      // Video: stays in the fullscreen <video> element. Use muted so autoplay
      // always goes through; the <audio> element is carrying the actual sound.
      video.srcObject = stream;
      video.muted = true;
      const vp = video.play();
      if (vp && typeof vp.catch === 'function') {
        vp.catch(err => setStatus(`video.play failed: ${err && err.name || err}`));
      }
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
