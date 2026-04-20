# Changelog

All notable changes to ScreenCast are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/ddagunts/ScreenCast/compare/0.3...HEAD
