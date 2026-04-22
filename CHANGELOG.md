# Changelog

All notable changes to ScreenCast are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0]

### Added
- WebRTC mode — low-latency (sub-second) screen mirroring alongside the
  existing HLS path, reachable from the overflow menu on the Cast screen.
  Uses a custom Cast receiver; ships with a default App ID (`9098830C`)
  pointing at the project's hosted receiver, so it works with zero setup.
  Users who prefer to host their own receiver can register a URL at
  cast.google.com and paste the resulting 8-character App ID in the
  WebRTC screen to override. Single Chromecast per WebRTC cast; no
  pause/play/seek or volume controls (WebRTC has no media transport
  concept).

### Changed
- Renamed "Phase 1 / Phase 2" terminology to "HLS mode / WebRTC mode"
  across the README, receiver docs, UI labels, and code comments.


## [0.5.4]

### Changed
- Change default sync settings


## [0.5.3]

### Changed
- Sync settings are now dropdowns instead of sliders. Slider
  precision was a poor fit for "pick a cadence" / "pick a
  tolerance" — every other value was indistinguishable from the
  ones around it. New option lists: interval {5, 10, 15, 20, 25,
  30, 45, 60, 120, 300} s; threshold {15, 20, 25, 30, 45, 60, 90}
  ms. Default values bumped to 30 s interval / 20 ms threshold,
  both of which land in the middle of each list. Sync start is
  now ON by default since most real multi-receiver setups want
  alignment anyway — users who don't can still disable it.

## [0.5.2]

### Fixed
- HTTP server binding to the wrong interface when a VPN is active.
  Some VPN clients (e.g. Rethink/bravedns) publish a dual-transport
  network with `Transports: WIFI|VPN` whose first IPv4 LinkAddress
  is the tunnel endpoint (10.x). `NetworkUtils.getWifiIpAddress`
  filtered only by `TRANSPORT_WIFI`, so we'd bind the Ktor server
  to the VPN IP and tell the Chromecast to fetch from an address it
  couldn't route to — casting would LAUNCH but never LOAD. Fix:
  also require `NET_CAPABILITY_NOT_VPN`, which excludes those mirror
  networks and lands us on the real wlan0.

## [0.5.1]

### Added
- Heartbeat liveness check. `CastSession` now tracks the timestamp
  of the most recent receiver-originated heartbeat traffic
  (PONG replies and receiver-initiated PINGs) and declares the
  session dead after 15 s of silence. Catches TCP half-open states
  where `send()` keeps succeeding but the Chromecast is gone —
  previously the session would sit in CASTING forever.
- "Paired devices" section in Settings. Lists every host with a
  TOFU-pinned TLS fingerprint and exposes a Forget button per row
  so a replaced or factory-reset Chromecast can be re-pinned on
  the next successful handshake. `CastCertPinStore` grew
  `pinnedHosts()` and `forget(host)` to back it.
- Unit + Robolectric test suite. 51 tests covering the Cast V2
  protobuf codec, JSON message builders, `StreamConfig` derived
  fields, `HlsSegmenter` playlist format, `StreamConfigStore`
  roundtrip (including the legacy `fine_volume_step` stripper),
  and `CastCertPinStore` (pin / get / forget / `pinnedHosts`).
  Runs via `./gradlew :app:testDebugUnitTest`.

## [0.5.0]

### Added
- Multi-device casting. Up to 4 Chromecasts can subscribe to the same
  HLS stream in parallel; each receiver has its own Cast V2 session
  with independent transport controls and volume. The capture
  pipeline is started once on the first device and reused for the
  rest, so adding a second receiver doesn't retrigger the
  `MediaProjection` consent dialog. An explicit "Stop all" ends every
  cast, and per-device Stop buttons leave the others running.
- Cross-receiver sync. A new "Sync start" Settings toggle pauses any
  running casts when a new device joins, waits for the new session to
  reach ready state, coordinates a `SEEK` across all sessions to the
  same offset (with `resumeState=PLAYBACK_PAUSE`), and then fires
  `PLAY` on every receiver in parallel.
- Continuous sync maintenance. While Sync start is enabled, a
  background loop in `CastForegroundService` periodically polls each
  receiver's `currentTime`, pauses every session, seeks them all to
  the laggard's offset, and resumes playback in parallel. The check
  interval (default 15 s) and drift threshold (default 15 ms) are
  exposed as Settings sliders and can be tuned live via
  `ACTION_UPDATE_SYNC_CONFIG` without ending the cast.
- Per-receiver "Fine" volume toggle. The 1%-step fine adjustment used
  to be a global preference; now each active device card has its own
  toggle so you can fine-trim one receiver while leaving coarser
  ±5% control on the others.

## [0.4.3]

### Added
- Screen wake lock held by `CastForegroundService` for the lifetime
  of a cast session. The phone no longer sleeps mid-cast, so
  `MediaProjection` keeps feeding frames while the phone is set
  down. `SCREEN_DIM_WAKE_LOCK` so the user's brightness preference
  is respected; released in `teardown()`.
- Fine-grained volume adjustment option. A "Fine" toggle in the
  volume card swaps the ± step from 5% to 1%. Persisted via
  `StreamConfigStore` alongside the other stream prefs.

### Fixed
- Theme recomposition on runtime dark-mode toggle. The dynamic
  color scheme was reading `LocalContext.current.resources.configuration`,
  which Compose's `LocalContextConfigurationRead` lint rule flags —
  `LocalContext` reads don't invalidate on configuration changes, so
  the color scheme could go stale until the Activity restarted.
  Switched to `LocalConfiguration.current.uiMode`. CI lint now passes.

## [0.4.1]

### Added
- README now renders the F-Droid phone screenshots (device list,
  settings, and a live cast) from
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`, so
  GitHub visitors get the same visual tour F-Droid does.

## [0.4.0]

### Changed
- Material 3 UI refresh. The three-tab `PrimaryTabRow` layout collapses
  into a single Cast surface with a `TopAppBar`; Settings moves behind
  a gear `IconButton` and Logs behind an overflow menu, each with an
  `ArrowBack` nav icon to return. Dynamic color (Material You) applies
  on Android 12+, falling back to `darkColorScheme()` on older devices.
  The Cast screen now uses `ElevatedCard` + `AssistChip` for status
  (with a colored dot mirroring Live / Paused / Buffering / Error),
  `FilledIconButton` / `FilledTonalIconButton` for transport, and a
  `ListItem` device picker with `Cast`/`CastConnected` glyphs.
  The volume control is a ±5 % button pair (`FilledTonalIconButton`
  with `Add`/`Remove` glyphs) flanking a large percent readout — the
  earlier draggable `Slider` tended to move in visible steps rather
  than tracking the finger, and discrete taps match the Chromecast's
  native 5 % increment. `Copy URL` and `Copy logs` stay as textual
  `TextButton`s. Settings wraps each slider in its own `SettingCard`.
- Pulled in `androidx.compose.material:material-icons-extended` for
  glyphs missing from icons-core (`Pause`, `Stop`, `ContentCopy`,
  `Cast`, `CastConnected`, `VolumeUp`/`VolumeOff`). R8 strips unused
  icons from release builds.

### Added
- Transport controls over the Cast V2 `media` namespace: Play / Pause
  buttons in the control screen and on the ongoing notification. The
  phone reflects the receiver's `playerState` (PLAYING / PAUSED /
  BUFFERING / IDLE) as the source of truth — we never optimistically
  flip local state on a button press.
- Volume and mute controls over the Cast V2 `receiver` namespace. The
  slider drives the TV in real time via a conflated channel throttled
  to ~10 Hz, with a guaranteed final send on release so the TV lands
  on the exact finger-up position. Unsolicited `RECEIVER_STATUS`
  echoes snap the UI back to the device state when no drag is in
  progress.
- `CastVolume` model surfacing `controlType` — sliders disable
  themselves when the receiver reports `"fixed"` (e.g. some soundbars
  and audio extractors that own volume independently).
- `CONTROLS.md` — implementation plan for phone-as-remote support,
  including the distinction between Cast V2 (transport + volume on
  port 8009) and the separate Android TV Remote Service v2 (D-pad,
  Home, Back on ports 6466/6467 with its own pairing flow) so future
  work on the D-pad remote starts from the right premise.

### Security
- Added `res/xml/network_security_config.xml` wired via
  `AndroidManifest.xml`. Defense-in-depth that asserts the app's
  network posture: `cleartextTrafficPermitted="false"` and an empty
  `<trust-anchors/>` make any accidental outbound HTTP(S) via
  `HttpURLConnection` / `OkHttp` / `WebView` fail loudly.
  - Unaffected and intentional: the TLS connection to the Chromecast
    on port 8009 (uses its own scoped `SSLContext` + TOFU fingerprint
    pin store), mDNS discovery over UDP multicast, and the inbound
    Ktor HLS server (NSC does not govern `ServerSocket`s).

[0.6.0]: https://github.com/ddagunts/ScreenCast/compare/v0.5.4...v0.6.0
[0.5.4]: https://github.com/ddagunts/ScreenCast/compare/v0.5.3...v0.5.4
[0.5.3]: https://github.com/ddagunts/ScreenCast/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/ddagunts/ScreenCast/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/ddagunts/ScreenCast/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/ddagunts/ScreenCast/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/ddagunts/ScreenCast/compare/v0.4.1...v0.4.3
[0.4.1]: https://github.com/ddagunts/ScreenCast/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ddagunts/ScreenCast/compare/v0.3...v0.4.0
