package com.rumor.mesh.core.routing

/**
 * O98 Phase 3b — pick the quietest 5 GHz operating frequency for an autonomous
 * group. A P2P GO on the driver default (ch36) collided with a bystander's
 * 5 GHz network in the field; Wi-Fi is CSMA/CA so this is a contention problem,
 * solved by landing on the least-occupied lane.
 *
 * Candidates are non-DFS 5 GHz channels only (DFS requires radar-detect
 * behaviour a P2P GO can't provide). UNII-3 first: consumer routers default to
 * the low UNII-1 channels, so the top of the band is usually empty. If every
 * candidate is occupied, the min-count pick lands us co-channel with the
 * fewest neighbours — co-channel shares politely via CSMA; adjacent-channel
 * overlap is what actually corrupts frames.
 *
 * Pure logic; the caller (transport) feeds it AP centre frequencies from
 * `WifiManager.getScanResults()`.
 */
object ChannelSelector {

    /** ch 149/153/157/161/165 (UNII-3), then ch 36/40/44/48 (UNII-1). */
    val CANDIDATE_FREQS_MHZ = listOf(5745, 5765, 5785, 5805, 5825, 5180, 5200, 5220, 5240)

    /**
     * The candidate with the fewest observed APs; ties break toward the earlier
     * candidate (UNII-3 preferred). An empty scan yields the first candidate.
     */
    fun quietestFrequency(observedApFrequenciesMhz: List<Int>): Int {
        val counts = observedApFrequenciesMhz.groupingBy { it }.eachCount()
        return CANDIDATE_FREQS_MHZ.minByOrNull { counts[it] ?: 0 } ?: CANDIDATE_FREQS_MHZ.first()
    }
}
