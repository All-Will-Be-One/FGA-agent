package io.github.fate_grand_automata.prefs.core

import kotlinx.serialization.Serializable

@Serializable
data class OwnPartyMember(
    val id: Int,
    val names: Map<String, String>,
    val equipment: OwnPartyEquipment = OwnPartyEquipment()
) {
    init {
        require(id > 0 && names.isNotEmpty() && names.values.none(String::isBlank))
    }

    fun label(region: String): String = names[region] ?: names["NA"] ?: names["JP"] ?: names.values.first()
}

@Serializable
enum class EquipmentAction { Keep, Remove, Equip }

@Serializable
data class OwnPartyEquipment(
    val action: EquipmentAction = EquipmentAction.Keep,
    val id: Int? = null,
    val names: Map<String, String> = emptyMap(),
    val requireMaxLimitBreak: Boolean = false
) {
    init {
        if (action == EquipmentAction.Equip) {
            require(id != null && id > 0 && names.isNotEmpty() && names.values.none(String::isBlank))
        } else {
            require(id == null && names.isEmpty() && !requireMaxLimitBreak)
        }
    }

    fun label(region: String): String? = names[region] ?: names["NA"] ?: names["JP"] ?: names.values.firstOrNull()
}

@Serializable
data class OwnPartyLineup(val slots: Map<Int, OwnPartyMember> = emptyMap()) {
    init {
        require(slots.keys.all { it in SLOT_RANGE })
        require(slots.values.map { it.id }.distinct().size == slots.size)
    }

    fun otherIds(slot: Int): Set<Int> =
        slots.filterKeys { it != slot }.values.mapTo(mutableSetOf()) { it.id }

    fun withMember(slot: Int, member: OwnPartyMember?): OwnPartyLineup {
        require(slot in SLOT_RANGE)
        return when {
            member == null -> OwnPartyLineup(slots - slot)
            member.id in otherIds(slot) -> this
            else -> OwnPartyLineup(slots + (slot to member))
        }
    }

    companion object {
        val SLOT_RANGE = 1..6
    }
}
