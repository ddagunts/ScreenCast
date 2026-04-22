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

// Single RTCPeerConnection per receiver lifetime. Empty iceServers list —
// both peers live on the same LAN, so host candidates are enough; no STUN
// round-trip, no TURN relays.
const pc = new RTCPeerConnection({ iceServers: [] });

pc.ontrack = (evt) => {
  console.log('ontrack', evt.track && evt.track.kind, evt.streams && evt.streams[0]);
  const stream = (evt.streams && evt.streams[0]) || new MediaStream([evt.track]);
  video.srcObject = stream;
  // Surface track arrival but DO NOT hideStatus() yet — ontrack fires right
  // after setRemoteDescription succeeds (before createAnswer/setLocalDescription
  // and long before ICE completes). Hiding here would mask any failure in the
  // subsequent signaling steps. We hide only when onconnectionstatechange
  // reports 'connected'.
  setStatus(`track: ${evt.track && evt.track.kind || '?'}`);
  // Trusted CAF context permits unmuted autoplay; fall back to muted if the
  // browser rejects it. Surface unmuted failure to status so we can see it.
  video.muted = false;
  const p = video.play();
  if (p && typeof p.catch === 'function') {
    p.catch(err => {
      setStatus(`play unmuted failed: ${err && err.name || err}; retrying muted`);
      video.muted = true;
      video.play().catch(e => setStatus(`play muted also failed: ${e && e.name || e}`));
    });
  }
};

pc.onicecandidate = (evt) => {
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

pc.onconnectionstatechange = () => {
  const s = pc.connectionState;
  console.log('pc state', s);
  if (s === 'connected') hideStatus();
  else if (s === 'failed') setStatus('WebRTC connection failed');
  else if (s === 'disconnected') setStatus('Sender disconnected');
  else setStatus(`pc state: ${s}`);
};
pc.oniceconnectionstatechange = () => {
  setStatus(`ice: ${pc.iceConnectionState}`);
};

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
      try { pc.close(); } catch (_) {}
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
