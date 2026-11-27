package io.github.fate_grand_automata.scripts.modules

/** UI indexes are zero-based; the last game preset is exclusively for native Agent launches. */
object AgentPartyGate {
    const val PRESET_COUNT = 15
    const val RESERVED_INDEX = PRESET_COUNT - 1
    const val RESERVED_NUMBER = PRESET_COUNT
    val manualIndices = 0 until RESERVED_INDEX

    enum class Result { Verified, SlotUnverified, EditorRequired }

    internal fun prepare(
        hasPendingEdits: Boolean,
        readSlot: () -> Int?,
        selectSlot: (Int) -> Unit
    ): Result {
        val observed = readSlot()?.takeIf { it in 0 until PRESET_COUNT }
            ?: return Result.SlotUnverified
        if (observed != RESERVED_INDEX) {
            selectSlot(RESERVED_INDEX)
            if (readSlot() != RESERVED_INDEX) return Result.SlotUnverified
        }
        return if (hasPendingEdits) Result.EditorRequired else Result.Verified
    }

    /** Accept only a marker close to a known slot; never round an ambiguous gap to a slot. */
    internal fun indexFromMarker(centerX: Int, slotXs: List<Int>): Int? =
        slotXs.withIndex().singleOrNull { (_, x) -> kotlin.math.abs(x - centerX) <= 15 }?.index
}


/** A requested edit, not evidence that the live roster has changed. */
data class AgentPartyMemberPlan(
    val position: Int,
    val servantId: Int,
    val equipmentAction: AgentEquipmentAction,
    val craftEssenceId: Int?,
    val requireMaxLimitBreak: Boolean = false
)

enum class AgentEquipmentAction { Keep, Remove, Equip }

enum class AgentEquipmentFilter { EventBonusFirst }

/** Max limit break can satisfy a normal request, but never the other way around. */
enum class AgentLimitBreak { Normal, Max }

object AgentEquipmentCompatibility {
    fun accepts(required: AgentLimitBreak, observed: AgentLimitBreak, allowUpgrade: Boolean): Boolean =
        required == observed ||
            (allowUpgrade && required == AgentLimitBreak.Normal && observed == AgentLimitBreak.Max)
}
