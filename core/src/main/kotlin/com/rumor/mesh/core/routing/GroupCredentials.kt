package com.rumor.mesh.core.routing

import com.rumor.mesh.core.platform.Sha256

/**
 * O98 Phase 3b — deterministic Wi-Fi Direct group credentials.
 *
 * Autonomous `createGroup` is the only path Android honors an operating channel
 * on (field-verified 2026-07-13), but its groups require a WPS invitation-accept
 * to join on some devices (the Moto never auto-joins). The fix is credentials
 * every mesh member can derive from the host's userId alone: the host brings
 * the group up with them, clients join by networkName+passphrase — no WPS
 * prompt, no coordination message.
 *
 * These credentials are deliberately NOT a secret: anyone who knows a userId
 * (which gossip broadcasts by design) can derive them and join the group. That
 * is the point — the group is a transport lane, not a trust boundary. Identity
 * and authenticity remain exactly where they always were: the HELLO Ed25519
 * challenge-response and per-message signatures. A stranger joining the group
 * gets to speak TCP to us, which the negotiated-connect path allowed too.
 *
 * KDF domain tags `rumor-o98-net-v1:` / `rumor-o98-psk-v1:` are reserved
 * forever in docs/RENAMED_FIELDS_NEVER_REUSE.md.
 */
object GroupCredentials {

    data class Credentials(val networkName: String, val passphrase: String)

    /** A backbone group SSID: `DIRECT-` + 12 lowercase hex of the host hash. */
    val BACKBONE_SSID_REGEX = Regex("^DIRECT-[0-9a-f]{12}$")

    /**
     * Derive the credentials for a group hosted by [hostUserId]. Pure function —
     * host and every prospective client independently compute identical values.
     *
     * networkName satisfies Android's constraint (`DIRECT-` prefix, first two
     * suffix chars alphanumeric, ≤32 chars total); passphrase satisfies WPA2's
     * 8–63 printable ASCII.
     */
    fun forHost(hostUserId: String): Credentials {
        val net = Sha256.digest("rumor-o98-net-v1:$hostUserId".toByteArray(Charsets.UTF_8))
        val name = "DIRECT-" + hex(net, 12)
        return Credentials(networkName = name, passphrase = passphraseFor(name))
    }

    /**
     * The passphrase is derived from the NETWORK NAME, not the host userId, so
     * a node that merely sees a backbone SSID in a Wi-Fi scan can join it
     * prompt-free without knowing who hosts it — the bootstrap case (a fresh
     * node beside an established backbone has no beacons yet, so it can't know
     * the host's userId; a plain connect() into the formed group instead
     * raises a manual invitation on some OEMs — field-observed on the Moto).
     * Any radio-range observer can equally derive it; that was already true of
     * the userId-derived scheme (userIds are gossiped) and is the accepted
     * O51 posture — the group is a transport lane, identity is HELLO's job.
     */
    fun passphraseFor(networkName: String): String =
        hex(Sha256.digest("rumor-o98-psk-v2:$networkName".toByteArray(Charsets.UTF_8)), 16)

    private fun hex(bytes: ByteArray, chars: Int): String =
        buildString {
            for (b in bytes) {
                append(HEX[(b.toInt() shr 4) and 0xf])
                append(HEX[b.toInt() and 0xf])
                if (length >= chars) break
            }
        }.take(chars)

    private const val HEX = "0123456789abcdef"
}
