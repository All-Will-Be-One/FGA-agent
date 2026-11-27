package io.github.fate_grand_automata.scripts.modules

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentEquipmentCompatibilityTest {
    @Test
    fun `same break matches while upgrade is one way only`() {
        val max = AgentLimitBreak.Max
        val normal = AgentLimitBreak.Normal
        assertTrue(AgentEquipmentCompatibility.accepts(max, max, false))
        assertTrue(AgentEquipmentCompatibility.accepts(normal, normal, false))
        assertFalse(AgentEquipmentCompatibility.accepts(normal, max, false))
        assertTrue(AgentEquipmentCompatibility.accepts(normal, max, true))
        assertFalse(AgentEquipmentCompatibility.accepts(max, normal, true))
        assertFalse(AgentEquipmentCompatibility.accepts(max, normal, false))
    }
}
