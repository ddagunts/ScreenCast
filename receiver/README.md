# ScreenCast WebRTC receiver

Static HTML + JS loaded by the Chromecast's Chrome instance. Accepts a WebRTC
offer from the Android sender over a custom Cast namespace and renders the
remote video track fullscreen.

The app ships with a default App ID (`9098830C`) pointing at the project's
hosted receiver, so most users don't need to do anything here. The rest of this
file is only relevant if you're hosting your own receiver.

## Hosting your own receiver

The page must be served over HTTPS at a stable URL that you register with
Google. Typical path: enable GitHub Pages on this repository with source set
to the `receiver/` directory (or the `gh-pages` branch), then your receiver is
at e.g. `https://<user>.github.io/<repo>/`.

After the URL is live, register it at <https://cast.google.com/publish/>:

1. Add a new app → "Custom receiver".
2. Paste the receiver URL.
3. Add the Chromecast's serial number under "Devices" so you can test without
   publishing.
4. Google issues an 8-character App ID. Paste it into the app's WebRTC screen
   to override the default.

## Files

- `index.html` — single `<video>` element, loads the CAF Receiver framework
  from Google's CDN and then `receiver.js`.
- `receiver.js` — registers our custom namespace, handles OFFER/ANSWER/ICE.

## Namespace

`urn:x-cast:io.github.ddagunts.screencast.webrtc` — keep in sync with
`WEBRTC_NAMESPACE` in
`app/src/main/java/io/github/ddagunts/screencast/webrtc/WebRtcConfig.kt`.
