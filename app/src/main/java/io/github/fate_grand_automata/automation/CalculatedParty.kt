package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.modules.AgentPartyGate
import io.github.fga.automation.RpcFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

/** Only structured IDs and the constrained native fixed-recipe grammar cross the bridge. */
@Serializable
data class CalculatedEssence(val id: Int, val mlb: Boolean)

@Serializable
data class CalculatedMember(val slot: Int, val servantId: Int, val ce: CalculatedEssence?)

@Serializable
data class CalculatedFormation(val supportSlot: Int, val members: List<CalculatedMember>)

@Serializable
data class CalculatedParty(
    val version: Int,
    val preset: Int,
    val region: String,
    val questKey: Long,
    val formation: CalculatedFormation,
    val support: CalculatedMember,
    val mysticCodeId: Int?,
    val skillCommand: String,
    val scriptSha256: String,
    val replayRequestSha256: String,
    val readyToStart: Boolean,
    val supportAppendMax: List<Int> = emptyList(),
    val supportAppendUnlocked: List<Int> = emptyList()
) {
    val actionSha256: String get() = sha256(canonical(Json.encodeToJsonElement(this)).toString())
    val configId: String get() = "agent-recipe-" + actionSha256.take(32)
    val configName: String get() = "Agent XV $questKey ${scriptSha256.take(8)}" +
        (if (supportAppendMax.isEmpty()) "" else " A${supportAppendMax.joinToString("/")} MAX") +
        (if (supportAppendUnlocked.isEmpty()) "" else " A${supportAppendUnlocked.joinToString("/")} UNLOCKED")

    companion object {
        private val key = Regex("[a-f0-9]{64}")
        private val turn = Regex("(?:t[1-3]|[a-l][1-3]?)*[4-6]{1,3}")
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun read(element: JsonElement): CalculatedParty {
            val plan = Json.decodeFromJsonElement(serializer(), element)
            val (supportSlot, members) = plan.formation
            require(plan.version == 1 && plan.region == "JP" && plan.preset == AgentPartyGate.RESERVED_NUMBER &&
                !plan.readyToStart && plan.questKey > 0 && (plan.mysticCodeId ?: 1) > 0) {
                "Expected a prepare-only v1 plan for JP party XV"
            }
            require(supportSlot in 1..3 && plan.support.slot == supportSlot &&
                members.size in 2..5 && members.map { it.slot }.toSet().size == members.size &&
                members.map { it.servantId }.toSet().size == members.size &&
                members.count { it.slot in 1..3 } == 2 && members.none { it.slot == supportSlot } &&
                (members + plan.support).all { it.slot in 1..6 && it.servantId > 0 && (it.ce?.id ?: 1) > 0 }) {
                "Expected distinct own servants, two owned starters, and one frontline support"
            }
            require(key.matches(plan.replayRequestSha256) && plan.skillCommand.length in 1..1024 &&
                sha256(plan.skillCommand) == plan.scriptSha256) { "Invalid replay or script digest" }
            val waves = plan.skillCommand.split(",#,")
            require(waves.size in 1..20 && waves.all { wave ->
                wave.split(",").all { code ->
                    turn.matches(code) && code.takeLastWhile { it in '4'..'6' }.let { it.toSet().size == it.length }
                }
            }) { "Only reviewed skill, enemy target and ordered NP commands are accepted" }
            require(plan.supportAppendMax == plan.supportAppendMax.distinct().sorted() &&
                plan.supportAppendMax.all { it in 1..5 }) { "Invalid required Append Skill slots" }
            require(plan.supportAppendUnlocked == plan.supportAppendUnlocked.distinct().sorted() &&
                plan.supportAppendUnlocked.all { it in 1..5 && it !in plan.supportAppendMax }) {
                "Invalid required unlocked Append Skill slots"
            }
            AutoSkillCommand.parse(plan.skillCommand)
            if (plan.support.ce != null)
                throw RpcFailure("UnsupportedSupportEquipment",
                    "The native editor cannot yet require an exact catalog-ID CE on a friend")
            return plan
        }
    }
}
