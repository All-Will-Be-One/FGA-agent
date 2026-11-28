package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.enums.SupportClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RecipeSupportClassTest {
    private val id = "agent-recipe-" + "a".repeat(32)
    private val legacy = mapOf("support_mode" to "Preferred", "battle_config_server" to "Jp",
        "autoskill_party" to "14", "support_pref_servant_list" to setOf("atlas:svt:504500"))

    @Test fun mapsTheSevenStandardClassesAndExtraTab() {
        for (tab in listOf(SupportClass.Saber, SupportClass.Archer, SupportClass.Lancer, SupportClass.Rider,
            SupportClass.Caster, SupportClass.Assassin, SupportClass.Berserker)) {
            assertEquals(tab, recipeSupportClass(tab.name.lowercase()))
        }
        for (name in listOf("shielder", "moonCancer", "beastEresh", "pretender"))
            assertEquals(SupportClass.Extra, recipeSupportClass(name))
        assertFailsWith<IllegalArgumentException> { recipeSupportClass("") }
    }

    @Test fun onlyGeneratedConfigsWithASingleFriendAndAbsentClassAreBackfilled() {
        assertEquals(504500, legacySupportId(id, legacy))
        assertNull(legacySupportId("manual", legacy))
        assertNull(legacySupportId(id, legacy + (RECIPE_CLASS_KEY to "None")))
        assertNull(legacySupportId(id, legacy + (RECIPE_CLASS_KEY to "All")))
        assertNull(legacySupportId(id, legacy + ("support_pref_servant_list" to setOf("a", "b"))))
    }
}
