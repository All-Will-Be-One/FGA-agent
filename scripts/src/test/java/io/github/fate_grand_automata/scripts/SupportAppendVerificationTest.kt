package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.supportSelection.AppendDisplayCycle
import io.github.fate_grand_automata.scripts.supportSelection.AppendDisplayCycle.View
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.modules.Support
import io.github.fate_grand_automata.scripts.modules.SupportModeDecider
import io.github.fate_grand_automata.scripts.modules.SupportSelectionLoop
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.SupportSelectionProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportAppendVerificationTest {
    private class Screen(var view: View) {
        var taps = 0
        var dropRestoration = false
        val cycle = AppendDisplayCycle(
            read = { view },
            tapAndWait = {
                taps++
                view = when (view) {
                    View.Normal -> View.Append
                    View.Append -> if (dropRestoration) View.Unknown else View.Both
                    View.Both -> View.Normal
                    View.Unknown -> View.Unknown
                }
            },
            waitForFrame = {}
        )
    }

    @Test fun `matching and rejected supports both restore normal display`() {
        for (match in listOf(true, false)) {
            val screen = Screen(View.Normal)
            val result = screen.cycle.checkAppend {
                assertEquals(View.Append, screen.view)
                match
            }
            assertEquals(match, result)
            assertEquals(3, screen.taps)
            assertEquals(View.Normal, screen.view)
        }
    }

    @Test fun `starts safely from existing append or mixed display`() {
        val append = Screen(View.Append)
        assertTrue(append.cycle.checkAppend { append.view == View.Append })
        assertEquals(5, append.taps)
        val mixed = Screen(View.Both)
        assertFalse(mixed.cycle.checkAppend { false })
        assertEquals(4, mixed.taps)
    }

    @Test fun `unknown mode stops without selecting or tapping`() {
        val screen = Screen(View.Unknown)
        val failure = assertFailsWith<AutoBattle.BattleExitException> {
            screen.cycle.checkAppend { error("uncertain view must never be inspected") }
        }
        assertEquals(AutoBattle.ExitReason.SupportSkillDisplayUnverified, failure.reason)
        assertEquals(0, screen.taps)
    }

    @Test fun `hard requirement forbids first row and manual fallback after exhaustion`() {
        val provider = mockk<SupportSelectionProvider>()
        val decider = mockk<SupportModeDecider>()
        every { decider.decide(SupportSelectionModeEnum.Preferred) } returns provider
        val loop = mockk<SupportSelectionLoop>()
        every { loop.select(provider, true) } returns false
        val prefs = mockk<ISupportPreferences>()
        every { prefs.appendMaxSlots } returns setOf("2", "5")
        val failure = assertFailsWith<AutoBattle.BattleExitException> {
            Support(decider, loop, prefs).selectSupport(SupportSelectionModeEnum.First)
        }
        assertEquals(AutoBattle.ExitReason.SupportRequirementsUnmet, failure.reason)
        verify(exactly = 1) { decider.decide(any()) }
        verify(exactly = 0) { prefs.fallbackTo }
    }

    @Test fun `a matching candidate is not returned after failed restoration`() {
        val screen = Screen(View.Normal).apply { dropRestoration = true }
        assertFailsWith<AutoBattle.BattleExitException> { screen.cycle.checkAppend { true } }
    }
}
