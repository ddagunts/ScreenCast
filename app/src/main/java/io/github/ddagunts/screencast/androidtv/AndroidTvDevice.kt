package io.github.ddagunts.screencast.androidtv

// Identifies an Android TV / Google TV target discovered over mDNS as
// `_androidtvremote2._tcp`. The remote-control port is always advertised
// (default 6466); the pairing port is hard-coded (6467) — the protocol
// doesn't advertise it.
//
// `bleMac` comes from the `bt`/`bs` TXT record where present and is the
// only stable identifier for a device whose IP changes on DHCP renew. We
// fall back to friendly-name when the TXT record is missing (some
// firmwares omit it; documented in the plan as an open question).
data class AndroidTvDevice(
    val name: String,
    val host: String,
    val port: Int = 6466,
    val pairingPort: Int = 6467,
    val modelName: String? = null,
    val bleMac: String? = null,
)
