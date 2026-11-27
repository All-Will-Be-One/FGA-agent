package io.github.fate_grand_automata.scripts.prefs

import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum

interface ISupportPreferences {
    val friendNames: List<String>
    val preferredServants: List<String>
    val mlb: Boolean
    val preferredCEs: List<String>
    val friendsOnly: Boolean
    val selectionMode: SupportSelectionModeEnum
    val fallbackTo: SupportSelectionModeEnum
    val supportClass: SupportClass
    val alsoCheckAll: Boolean

    val maxAscended: Boolean

    val skill1Max: Boolean
    val skill2Max: Boolean
    val skill3Max: Boolean

    /** 1-based append slots required at Lv.10; persisted only by an explicit Agent recipe. */
    val appendMaxSlots: Set<String> get() = emptySet()
    val appendUnlockedSlots: Set<String> get() = emptySet()

    val grandServant: Boolean
    val bondCEEffect: BondCEEffectEnum
    val requireBothNormalAndRewardMatch: Boolean
}