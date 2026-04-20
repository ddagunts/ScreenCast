# Changelog

All notable changes to ScreenCast are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
