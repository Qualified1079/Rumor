package com.rumor.mesh.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSelectorTest {

    @Test
    fun `empty scan prefers first unii3 candidate`() {
        assertEquals(5745, ChannelSelector.quietestFrequency(emptyList()))
    }

    @Test
    fun `occupied candidates are avoided`() {
        // Everything in UNII-3 except 5805 has a neighbour.
        val observed = listOf(5745, 5745, 5765, 5785, 5825)
        assertEquals(5805, ChannelSelector.quietestFrequency(observed))
    }

    @Test
    fun `tie breaks toward unii3`() {
        // One AP on every candidate: all counts equal → first candidate wins.
        assertEquals(5745, ChannelSelector.quietestFrequency(ChannelSelector.CANDIDATE_FREQS_MHZ))
    }

    @Test
    fun `saturated air lands co-channel with fewest neighbours`() {
        val observed = ChannelSelector.CANDIDATE_FREQS_MHZ.flatMap { listOf(it, it) } - 5220
        assertEquals(5220, ChannelSelector.quietestFrequency(observed))
    }

    @Test
    fun `non candidate frequencies are ignored`() {
        // 2.4 GHz and DFS traffic doesn't influence the pick.
        val observed = listOf(2412, 2437, 2462, 5300, 5500, 5745)
        assertEquals(5765, ChannelSelector.quietestFrequency(observed))
    }
}
