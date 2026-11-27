package io.github.fate_grand_automata.ui.battle_config_item

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.EquipmentAction
import io.github.fate_grand_automata.prefs.core.OwnPartyEquipment
import io.github.fate_grand_automata.prefs.core.OwnPartyLineup
import io.github.fate_grand_automata.prefs.core.OwnPartyMember
import io.github.fate_grand_automata.supportcatalog.CatalogKind
import io.github.fate_grand_automata.supportcatalog.SupportServantStore
import io.github.fate_grand_automata.ui.pref_support.SelectCatalogServantDialog
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.remember

@Composable
internal fun OwnPartyLineupPreference(config: BattleConfigCore) {
    var saved by config.ownPartyLineup.remember()
    var draft by remember(config.id) { mutableStateOf<OwnPartyLineup?>(null) }
    var picking by remember(config.id) { mutableStateOf<Int?>(null) }
    var equipmentSlot by remember(config.id) { mutableStateOf<Int?>(null) }
    var searchingEquipment by remember(config.id) { mutableStateOf(false) }
    val region = SupportServantStore.region(LocalConfiguration.current.locales[0])

    Preference(
        title = stringResource(R.string.own_party_title),
        summary = OwnPartyLineup.SLOT_RANGE.mapNotNull { slot ->
            saved.slots[slot]?.let { "$slot. ${it.label(region)}" }
        }.joinToString(" · ").ifBlank { stringResource(R.string.own_party_hint) },
        onClick = {
            draft = saved
            picking = null
            equipmentSlot = null
            searchingEquipment = false
        }
    )

    val lineup = draft ?: return
    val slot = picking
    val ceSlot = equipmentSlot
    val ceMember = ceSlot?.let(lineup.slots::get)
    if (ceSlot != null && ceMember != null) {
        var needMax by remember(ceSlot, ceMember.id) { mutableStateOf(ceMember.equipment.requireMaxLimitBreak) }
        fun updateEquipment(choice: OwnPartyEquipment) {
            draft = lineup.withMember(ceSlot, ceMember.copy(equipment = choice))
            equipmentSlot = null
            searchingEquipment = false
        }

        if (searchingEquipment) {
            SelectCatalogServantDialog(
                title = R.string.agent_party_ce_choose,
                confirmLabel = R.string.own_party_select,
                onDismiss = { searchingEquipment = false },
                kind = CatalogKind.CraftEssence,
                onSelected = { ce -> updateEquipment(OwnPartyEquipment(EquipmentAction.Equip, ce.id, ce.names, needMax)) }
            )
        } else {
            AlertDialog(
                onDismissRequest = { equipmentSlot = null },
                title = { Text(stringResource(R.string.agent_party_ce)) },
                text = {
                    Column {
                        Text(stringResource(R.string.agent_party_ce_filter_hint))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = needMax, onCheckedChange = { needMax = it })
                            Text(stringResource(R.string.agent_party_ce_require_max))
                        }
                        Text(stringResource(R.string.agent_party_ce_limit_compatibility))
                        if (ceMember.equipment.action == EquipmentAction.Equip &&
                            needMax != ceMember.equipment.requireMaxLimitBreak
                        ) {
                            TextButton(onClick = { updateEquipment(ceMember.equipment.copy(requireMaxLimitBreak = needMax)) }) {
                                Text(stringResource(R.string.agent_party_ce_save_requirement))
                            }
                        }
                        TextButton(onClick = { updateEquipment(OwnPartyEquipment()) }) {
                            Text(stringResource(R.string.agent_party_ce_keep))
                        }
                        TextButton(onClick = { updateEquipment(OwnPartyEquipment(EquipmentAction.Remove)) }) {
                            Text(stringResource(R.string.agent_party_ce_remove))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { searchingEquipment = true }) {
                        Text(stringResource(R.string.agent_party_ce_choose))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { equipmentSlot = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
        return
    }
    if (slot != null) {
        SelectCatalogServantDialog(
            title = R.string.own_party_choose,
            confirmLabel = R.string.own_party_select,
            onDismiss = { picking = null },
            disabledIds = lineup.otherIds(slot),
            onSelected = { servant ->
                val existing = lineup.slots[slot]?.takeIf { it.id == servant.id }?.equipment
                draft = lineup.withMember(slot, OwnPartyMember(servant.id, servant.names, existing ?: OwnPartyEquipment()))
                picking = null
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(stringResource(R.string.own_party_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.own_party_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(OwnPartyLineup.SLOT_RANGE.toList(), key = { it }) { position ->
                            val member = lineup.slots[position]
                            Column {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { picking = position },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val positionLabel = stringResource(
                                            if (position <= 3) R.string.own_party_front_slot
                                            else R.string.own_party_back_slot,
                                            position
                                        )
                                        val memberLabel = member?.label(region)
                                            ?: stringResource(R.string.own_party_unset)
                                        Text("$positionLabel: $memberLabel")
                                    }
                                    if (member != null) {
                                        TextButton(onClick = { draft = lineup.withMember(position, null) }) {
                                            Text(stringResource(R.string.clear))
                                        }
                                    }
                                }
                                if (member != null) {
                                    val equipment = member.equipment
                                    val label = when (equipment.action) {
                                        EquipmentAction.Keep -> stringResource(R.string.agent_party_ce_keep)
                                        EquipmentAction.Remove -> stringResource(R.string.agent_party_ce_remove)
                                        EquipmentAction.Equip -> equipment.label(region).orEmpty().let {
                                            if (equipment.requireMaxLimitBreak)
                                                stringResource(R.string.agent_party_ce_summary_max, it)
                                            else it
                                        }
                                    }
                                    TextButton(onClick = { equipmentSlot = position }) {
                                        Text(stringResource(R.string.agent_party_ce_summary, label))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saved = lineup
                    draft = null
                }) { Text(stringResource(R.string.own_party_save)) }
            },
            dismissButton = {
                TextButton(onClick = { draft = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
