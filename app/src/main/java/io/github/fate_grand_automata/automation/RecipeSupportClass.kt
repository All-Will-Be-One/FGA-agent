package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.supportcatalog.CatalogServant

internal const val RECIPE_CLASS_KEY = "autoskill_support_class"

internal fun recipeSupportClass(className: String): SupportClass {
    require(className.isNotBlank()) { "Missing friend servant class" }
    return when (className.lowercase()) {
        "saber" -> SupportClass.Saber
        "archer" -> SupportClass.Archer
        "lancer" -> SupportClass.Lancer
        "rider" -> SupportClass.Rider
        "caster" -> SupportClass.Caster
        "assassin" -> SupportClass.Assassin
        "berserker" -> SupportClass.Berserker
        else -> SupportClass.Extra
    }
}

private val recipeId = Regex("agent-recipe-[0-9a-f]{32}")

// Only generated JP Party XV configs with an unset class need the legacy backfill.
internal fun legacySupportId(id: String, values: Map<String, *>): Int? {
    if (!recipeId.matches(id) || RECIPE_CLASS_KEY in values ||
        values["battle_config_server"] != "Jp" || values["autoskill_party"] != "14" ||
        values["support_mode"] != "Preferred") return null
    val selected = values["support_pref_servant_list"] as? Set<*> ?: return null
    return (selected.singleOrNull() as? String)?.let(CatalogServant::idFromKey)
}
