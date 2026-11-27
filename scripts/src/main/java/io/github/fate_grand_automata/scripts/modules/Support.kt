package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.ManualSupportSelection
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class Support @Inject constructor(
    private val decider: SupportModeDecider,
    private val loop: SupportSelectionLoop,
    private val supportPrefs: ISupportPreferences
) {
    companion object {
        const val supportRegionToolSimilarity = 0.75
    }

    val requiresVerifiedAppend = supportPrefs.appendMaxSlots.isNotEmpty() || supportPrefs.appendUnlockedSlots.isNotEmpty()

    fun selectSupport(selectionMode: SupportSelectionModeEnum = supportPrefs.selectionMode) {
        val mode = if (requiresVerifiedAppend) SupportSelectionModeEnum.Preferred else selectionMode
        val provider = decider.decide(mode)

        if (!loop.select(provider, strictRefreshLimit = requiresVerifiedAppend)) {
            if (requiresVerifiedAppend)
                throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportRequirementsUnmet)
            val fallback = decider.decide(supportPrefs.fallbackTo)

            if (!loop.select(fallback)) {
                // give up
                ManualSupportSelection.select()
            }
        }
    }
}
