package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Battle
import io.github.fate_grand_automata.scripts.modules.PartySelection
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
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
    private val locations = mockk<Locations>()
    private val api = mockk<IFgoAutomataApi>(relaxed = true) {
        every { this@mockk.locations } returns this@RepeatSettlementTest.locations
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
            mockk(), mockk(), mockk(relaxed = true), mockk(), mockk(relaxed = true))
    }

    @Test fun `last run finishes even when starting mid battle without party verification`() {
        val controller = controller(limit = 1)
        state.nextStage()
        val exit = assertFailsWith<AutoBattle.BattleExitException> { controller.repeatQuest() }
        assertEquals(1, assertIs<AutoBattle.ExitReason.LimitRuns>(exit.reason).count)
        verify(exactly = 1) { prefs.updateCompletedRuns(1) }
    }

    private fun visibleRepeatButton(): Region {
        val search = Region(0, 0, 100, 100)
        val button = Region(10, 20, 30, 40)
        val pattern = mockk<Pattern>()
        every { locations.continueRegion } returns search
        every { locations.scriptArea } returns search
        every { api.images[Images.Repeat] } returns pattern
        every { with(api) { search.find(pattern) } } returns Match(button, 1.0)
        every { api.useSameSnapIn(any<() -> Unit>()) } returns Unit
        return button
    }

    @Test fun `mid battle takeover repeats with the same party and stops at the target`() {
        val controller = controller(limit = 2)
        val button = visibleRepeatButton()
        state.nextStage()
        controller.repeatQuest()
        assertEquals(1, state.runs)
        state.nextStage()
        val exit = assertFailsWith<AutoBattle.BattleExitException> { controller.repeatQuest() }
        assertEquals(2, assertIs<AutoBattle.ExitReason.LimitRuns>(exit.reason).count)
        verify(exactly = 1) { with(api) { button.click(1) } }
        verify(exactly = 0) { locations.partySelectionArray }
        verify(exactly = 0) { locations.selectedPartyRegion }
    }

    @Test fun `starting on the repeat prompt keeps the party without crediting the previous run`() {
        val controller = controller(limit = 1)
        val button = visibleRepeatButton()
        controller.repeatQuest()
        assertEquals(0, state.runs)
        verify(exactly = 1) { with(api) { button.click(1) } }
        verify(exactly = 0) { prefs.updateCompletedRuns(any()) }
        verify(exactly = 0) { locations.selectedPartyRegion }
    }
}
