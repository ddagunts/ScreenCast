# ScreenCast

An open-source Android app that casts the phone's screen to a Chromecast device over the local Wi-Fi network.  Vibe-coded and unchecked.

## How it works

- **Discovery**: `NsdManager` finds Chromecasts via mDNS (`_googlecast._tcp.local`).
- **Control channel**: TLS socket to Chromecast port 8009, Cast V2 protocol implemented in pure Kotlin (connection / heartbeat / receiver / media namespaces).
- **Capture**: Android `MediaProjection` + `MediaCodec` (H.264), optional audio via `AudioPlaybackCapture`.
- **Transport**: HLS served from an embedded Ktor HTTP server on the phone (port 8080). The playlist lives under a path gated by a 128-bit random token (e.g. `http://<phone-ip>:8080/c/<token>/stream.m3u8`); the token is generated fresh per cast session and is only valid while the foreground service is running. The Chromecast loads that URL through the Default Media Receiver (App ID `CC1AD845`). Expect ~5–10 s of latency.

> **Don't tighten CORS on the HTTP server.** `HttpStreamServer.kt` sets `anyHost()` on purpose: the hls.js player inside the Default Media Receiver fetches from a `google.com` origin we can't hardcode, and the URL-path token is the real authentication gate. Narrowing CORS will silently break playback.

## Requirements

- Android 8.0+ (API 26).
- A Chromecast on the same Wi-Fi network.
- No Google account or Cast SDK needed.

## Build

The Gradle wrapper is checked in. You only need a JDK 17 or 21 — Gradle itself is downloaded on first run.

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Pinned to Gradle 9.4.1; tested against Android Studio's bundled JBR 21.

Debug builds install side-by-side with release (`applicationId` suffix `.debug`).

### Release signing

Release builds require a keystore. Create one with `keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias screencast` and then either:

1. **Local:** create `keystore.properties` at the repo root (gitignored):
   ```properties
   storeFile=/absolute/path/to/release.jks
   storePassword=...
   keyAlias=screencast
   keyPassword=...
   ```
2. **CI:** set env vars `SCREENCAST_KEYSTORE_FILE`, `SCREENCAST_KEYSTORE_PASSWORD`, `SCREENCAST_KEY_ALIAS`, `SCREENCAST_KEY_PASSWORD`.

Then `./gradlew assembleRelease`. Without credentials `assembleRelease` still runs, but emits `app-release-unsigned.apk` — not installable. Debug builds are unaffected either way.

## Project layout

```
app/src/main/java/com/example/screencast/
├── cast/     # Cast V2 protocol (discovery, TLS channel, session FSM)
├── media/    # Screen capture, H.264 encode, HLS muxer, Ktor server
├── ui/       # Jetpack Compose UI + ViewModel
└── util/     # Networking, logging
```

## License

Apache-2.0.
