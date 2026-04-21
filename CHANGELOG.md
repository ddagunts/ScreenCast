# Changelog

All notable changes to ScreenCast are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.5.0]: https://github.com/ddagunts/ScreenCast/compare/v0.4.3...v0.5.0
[0.4.3]: https://github.com/ddagunts/ScreenCast/compare/v0.4.1...v0.4.3
[0.4.1]: https://github.com/ddagunts/ScreenCast/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ddagunts/ScreenCast/compare/v0.3...v0.4.0
