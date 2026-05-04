package io.github.ddagunts.screencast.androidtv

// Semantic key set the UI binds buttons to. Keeping this enum separate
// from the wire-level RemoteKeyCode lets us:
//   * Group D-pad / volume / media into UX-meaningful clusters without
//     leaking proto-style naming into the Compose layer.
//   * Add aliases (e.g. SLEEP and POWER both eventually mapping to one
//     wire code on TVs that don't separate them) without touching the
//     RemoteKeyCode enum.
enum class AndroidTvKey(val wire: RemoteKeyCode) {
    // D-pad cluster — the core navigation surface.
    DPadUp(RemoteKeyCode.DPAD_UP),
    DPadDown(RemoteKeyCode.DPAD_DOWN),
    DPadLeft(RemoteKeyCode.DPAD_LEFT),
    DPadRight(RemoteKeyCode.DPAD_RIGHT),
    DPadCenter(RemoteKeyCode.DPAD_CENTER),

    // System nav.
    Back(RemoteKeyCode.BACK),
    Home(RemoteKeyCode.HOME),
    Menu(RemoteKeyCode.MENU),
    Settings(RemoteKeyCode.SETTINGS),
    Search(RemoteKeyCode.SEARCH),
    Notifications(RemoteKeyCode.NOTIFICATION),
    Guide(RemoteKeyCode.GUIDE),
    Info(RemoteKeyCode.INFO),

    // Volume — most TVs accept either VOLUME_* or the dedicated
    // adjustVolume RPC; we wire the buttons to key events here and the
    // slider to RemoteSetVolumeLevel in AndroidTvSession.
    VolumeUp(RemoteKeyCode.VOLUME_UP),
    VolumeDown(RemoteKeyCode.VOLUME_DOWN),
    Mute(RemoteKeyCode.VOLUME_MUTE),

    // Media transport.
    MediaPlayPause(RemoteKeyCode.MEDIA_PLAY_PAUSE),
    MediaStop(RemoteKeyCode.MEDIA_STOP),
    MediaNext(RemoteKeyCode.MEDIA_NEXT),
    MediaPrevious(RemoteKeyCode.MEDIA_PREVIOUS),
    MediaRewind(RemoteKeyCode.MEDIA_REWIND),
    MediaFastForward(RemoteKeyCode.MEDIA_FAST_FORWARD),

    // Channel + power. TV_POWER vs POWER: TV_POWER is the soft-power
    // intent (TV listens regardless of source); POWER toggles the
    // current input. We expose both so the UI can decide.
    ChannelUp(RemoteKeyCode.CHANNEL_UP),
    ChannelDown(RemoteKeyCode.CHANNEL_DOWN),
    Power(RemoteKeyCode.POWER),
    TvPower(RemoteKeyCode.TV_POWER),
    Sleep(RemoteKeyCode.SLEEP),
    Wakeup(RemoteKeyCode.WAKEUP),
    Captions(RemoteKeyCode.CAPTIONS),
    AudioTrack(RemoteKeyCode.MEDIA_AUDIO_TRACK),
    TvInput(RemoteKeyCode.TV_INPUT),
}
