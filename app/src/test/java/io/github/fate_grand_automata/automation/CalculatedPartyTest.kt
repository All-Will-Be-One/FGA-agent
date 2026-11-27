package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalculatedPartyTest {
    private val script = "t3i254,t3l36,#,t2465"
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private val friend = CalculatedMember(2, 101, null)
    private fun plan() = CalculatedParty(
        1, 15, "JP", 9416410801,
        CalculatedFormation(2, listOf(
            CalculatedMember(1, 100, null),
            CalculatedMember(3, 102, CalculatedEssence(9400001, true)),
            CalculatedMember(5, 103, null)
        )),
        friend, 40, script, hash(script), "a".repeat(64), false
    )

    @Test fun parsesAgentTurnAndNpOrderThroughNativeParser() {
        val spec = CalculatedParty.read(Json.encodeToJsonElement(plan()))
        val native = AutoSkillCommand.parse(spec.skillCommand)
        assertEquals(2, native.stages.size)
        assertEquals(2, native.stages[0].size)
        assertEquals(listOf('5', '4'), (native[0, 0].last() as AutoSkillAction.Atk).nps.map { it.autoSkillCode })
        assertEquals(listOf('4', '6', '5'), (native[1, 0].last() as AutoSkillAction.Atk).nps.map { it.autoSkillCode })
        assertTrue(spec.configId.startsWith("agent-recipe-"))
    }

    @Test fun appendSlotsAreValidatedAndChangeConfigIdentity() {
        val base = plan()
        val required = CalculatedParty.read(Json.encodeToJsonElement(base.copy(supportAppendMax = listOf(2, 5))))
        assertEquals(listOf(2, 5), required.supportAppendMax)
        assertTrue(required.configId != base.configId)
        assertEquals(base.scriptSha256, required.scriptSha256)
        for (invalid in listOf(listOf(0), listOf(6), listOf(2, 2), listOf(5, 2))) {
            assertFailsWith<IllegalArgumentException> {
                CalculatedParty.read(Json.encodeToJsonElement(base.copy(supportAppendMax = invalid)))
            }
        }
    }

    @Test fun rejectsOtherPresetsResourceActionsAndHashMismatch() {
        fun denied(spec: CalculatedParty) = assertFailsWith<IllegalArgumentException> {
            CalculatedParty.read(Json.encodeToJsonElement(spec))
        }
        denied(plan().copy(preset = 4))
        denied(plan().copy(readyToStart = true))
        denied(plan().copy(skillCommand = "o14", scriptSha256 = hash("o14")))
        denied(plan().copy(skillCommand = "44", scriptSha256 = hash("44")))
        denied(plan().copy(skillCommand = "65"))
        denied(plan().copy(formation = CalculatedFormation(2, listOf(
            CalculatedMember(2, 100, null), CalculatedMember(1, 200, null)))))
    }
}
