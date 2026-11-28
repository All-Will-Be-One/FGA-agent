package io.github.fate_grand_automata.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class PartyTransitionTest {
    private val xv = PartyScreen.VerifiedXV
    private val menu = PartyScreen.Menu
    private val other = PartyScreen.Other

    private fun checkTransition(from: PartyScreen, to: PartyScreen, frames: List<PartyScreen>,
                                success: Boolean, vararg expectedTaps: Int) {
        var index = 0
        val taps = mutableListOf<Int>()
        val result = awaitPartyTransition(from, to, { frames.getOrElse(index) { frames.last() } },
            { index++ }, { taps += index })
        assertEquals(success, result)
        assertEquals(expectedTaps.toList(), taps)
    }

    @Test fun lateMenuAfterSave() =
        checkTransition(xv, menu, listOf(xv, xv) + List(45) { other } + menu, true, 1)

    @Test fun lateVerifiedEditorAfterReopening() =
        checkTransition(menu, xv, listOf(menu, menu) + List(45) { other } + xv, true, 1)

    @Test fun retryOnlyAfterStableSourceReturns() =
        checkTransition(xv, menu, listOf(xv, xv) + List(45) { other } + listOf(xv, other, xv, xv, menu),
            true, 1, 50)

    @Test fun neverAcceptAnUnverifiedReopen() =
        checkTransition(menu, xv, listOf(menu, menu, other), false, 1)

    @Test fun boundedRetriesOnIgnoredTaps() =
        checkTransition(menu, xv, listOf(menu), false, 1, 36, 71)

    @Test fun destinationAloneDoesNotProveAnAction() =
        checkTransition(xv, menu, listOf(menu), false)
}
