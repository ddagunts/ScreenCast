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

const context = cast.framework.CastReceiverContext.getInstance();
const options = new cast.framework.CastReceiverOptions();
// We don't use the CAF media pipeline at all — the sender never sends LOAD,
// so we disable the idle-timeout auto-shutdown. maxInactivity is in seconds;
// 24h is a reasonable "basically forever" value for a live screen cast.
options.maxInactivity = 24 * 60 * 60;
// Register our custom namespace. CAF refuses sendCustomMessage calls on a
// namespace it doesn't know about, so this has to come before start().
options.customNamespaces = Object.create(null);
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
  console.log('ontrack', evt.streams && evt.streams[0]);
  const stream = (evt.streams && evt.streams[0]) || new MediaStream([evt.track]);
  video.srcObject = stream;
  const p = video.play();
  if (p && typeof p.catch === 'function') p.catch(err => console.warn('video.play', err));
  hideStatus();
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
  console.log('pc state', pc.connectionState);
  if (pc.connectionState === 'connected') hideStatus();
  else if (pc.connectionState === 'failed') setStatus('WebRTC connection failed');
  else if (pc.connectionState === 'disconnected') setStatus('Sender disconnected');
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
      setStatus('Negotiating…');
      await pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      sendSignal({ type: 'ANSWER', sdp: answer.sdp });
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
    console.error('signal handler', msg && msg.type, err);
  }
});

context.start(options);
setStatus('Waiting for sender…');
// Announce readiness — the sender treats this as a hint but doesn't block
// on it (the OFFER is sent regardless after LAUNCH completes).
sendSignal({ type: 'READY' });
