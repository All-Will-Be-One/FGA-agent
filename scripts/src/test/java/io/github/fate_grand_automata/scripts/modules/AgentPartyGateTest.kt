package io.github.fate_grand_automata.scripts.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentPartyGateTest {
    @Test
    fun `switches only to 15 and verifies the selected marker again`() {
        val reads = listOf(3, 14).iterator()
        val clicks = mutableListOf<Int>()
        assertEquals(AgentPartyGate.Result.Verified, AgentPartyGate.prepare(false, { reads.next() }, { clicks += it }))
        assertEquals(listOf(14), clicks)
    }

    @Test
    fun `unknown state never clicks and failed readback never falls back`() {
        val clicks = mutableListOf<Int>()
        assertEquals(AgentPartyGate.Result.SlotUnverified, AgentPartyGate.prepare(false, { null }, { clicks += it }))
        assertEquals(emptyList(), clicks)
        assertEquals(AgentPartyGate.Result.SlotUnverified, AgentPartyGate.prepare(false, { 2 }, { clicks += it }))
        assertEquals(listOf(14), clicks)
    }

    @Test
    fun `already selected slot is left alone and pending edits block battle`() {
        assertEquals(AgentPartyGate.Result.Verified, AgentPartyGate.prepare(false, { 14 }) { error("Unexpected click") })
        assertEquals(AgentPartyGate.Result.EditorRequired, AgentPartyGate.prepare(true, { 14 }) { error("Unexpected click") })
        assertEquals((0..13).toList(), AgentPartyGate.manualIndices.toList())
    }

    @Test
    fun `marker between tabs is not interpreted as a slot`() {
        val points = (0..14).map { (it - 7) * 50 }
        assertEquals(14, AgentPartyGate.indexFromMarker(350, points))
        assertNull(AgentPartyGate.indexFromMarker(325, points))
        assertNull(AgentPartyGate.indexFromMarker(400, points))
    }
}
