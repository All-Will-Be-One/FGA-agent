package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.PartySelection
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RepeatSettlementTest {
    private val prefs = mockk<IPreferences>(relaxed = true)
    private val config = mockk<IBattleConfig>(relaxed = true)
    private val api = mockk<IFgoAutomataApi> {
        every { this@mockk.prefs } returns this@RepeatSettlementTest.prefs
    }
    private val state = BattleState()

    private fun controller(limit: Int): AutoBattle {
        every { prefs.selectedServerConfigPref.shouldLimitRuns } returns true
        every { prefs.selectedServerConfigPref.limitRuns } returns limit
        every { config.agentManagesParty } returns true
        val battle = Battle(api, mockk(relaxed = true), state, config, mockk(), mockk(),
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        return AutoBattle(mockk(), api, state, battle, mockk(), mockk(), PartySelection(api, config),
            mockk(), mockk(), mockk(), mockk(), mockk())
    }

    @Test fun `last run finishes even when starting mid battle without party verification`() {
        val controller = controller(limit = 1)
        state.nextStage()
        val exit = assertFailsWith<AutoBattle.BattleExitException> { controller.repeatQuest() }
        assertEquals(1, assertIs<AutoBattle.ExitReason.LimitRuns>(exit.reason).count)
        verify(exactly = 1) { prefs.updateCompletedRuns(1) }
    }

    @Test fun `unfinished target still requires verified party before repeat and counts battle once`() {
        val controller = controller(limit = 2)
        state.nextStage()
        repeat(2) {
            val exit = assertFailsWith<AutoBattle.BattleExitException> { controller.repeatQuest() }
            assertEquals(AutoBattle.ExitReason.AgentPartySlotUnverified, exit.reason)
        }
        assertEquals(1, state.runs)
        verify(exactly = 1) { prefs.updateCompletedRuns(1) }
    }

    @Test fun `entering on repeat screen without battling does not credit a run`() {
        val controller = controller(limit = 1)
        val exit = assertFailsWith<AutoBattle.BattleExitException> { controller.repeatQuest() }
        assertEquals(AutoBattle.ExitReason.AgentPartySlotUnverified, exit.reason)
        assertEquals(0, state.runs)
        verify(exactly = 0) { prefs.updateCompletedRuns(any()) }
    }
}
