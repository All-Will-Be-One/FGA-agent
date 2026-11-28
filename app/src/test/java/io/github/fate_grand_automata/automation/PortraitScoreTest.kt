package io.github.fate_grand_automata.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class PortraitScoreTest {
    @Test fun highestScoringVariantWinsRegardlessOfOrder() {
        val variants = listOf(listOf(.94, .92, .65), listOf(.96, .99, .71), listOf(.98, .68, .63))
        assertEquals(.96, bestPortraitScore(variants) { it })
        assertEquals(.96, bestPortraitScore(variants.reversed()) { it })
    }

    @Test fun oneGoodPatchFromEachOfTwoVariantsCannotPass() {
        assertEquals(.50, bestPortraitScore(listOf(listOf(.99, .45), listOf(.98, .50))) { it })
        assertEquals(Double.NEGATIVE_INFINITY, bestPortraitScore(emptyList<List<Double>>()) { it })
        assertEquals(Double.NEGATIVE_INFINITY, bestPortraitScore(listOf(listOf(.99))) { it })
    }
}
