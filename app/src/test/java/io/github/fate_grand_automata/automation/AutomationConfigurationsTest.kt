package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.modules.AgentEquipmentAction
import io.github.fate_grand_automata.scripts.modules.AgentPartyMemberPlan
import io.github.fate_grand_automata.runner.BattleRunValidationException
import java.lang.reflect.Proxy
import kotlinx.serialization.json.*
import kotlin.test.*

class AutomationConfigurationsTest {
    private fun config(id:String,plan:List<AgentPartyMemberPlan> = emptyList(),script:()->String):IBattleConfig = Proxy.newProxyInstance(
        IBattleConfig::class.java.classLoader,arrayOf(IBattleConfig::class.java)) { _,method,_ ->
        when(method.name) {
            "getId","getName" -> id
            "getServer" -> GameServer.Jp.Original
            "getSkillCommand" -> script()
            "getOwnPartyPlan" -> plan
            else -> error("Unneeded getter ${method.name}")
        }
    } as IBattleConfig

    @Test fun `bridge discovery keeps configurations when optional script hashes cannot read`() {
        val configs=listOf(config("broken-unselected") { error("old storage unavailable") },config("selected") {""})
        val rows=automationConfigurations(configs,{it.server},{"JP"}).getValue("configs").jsonArray
        assertEquals(listOf("broken-unselected","selected"),rows.map {it.jsonObject.getValue("id").jsonPrimitive.content})
        assertEquals(JsonNull,rows[0].jsonObject["scriptSha256"])
        assertEquals("old storage unavailable",rows[0].jsonObject.getValue("scriptHashWarning").jsonPrimitive.content)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",rows[1].jsonObject.getValue("scriptSha256").jsonPrimitive.content)
    }
    @Test fun `bridge describes event-first exact CE intent without claiming in-game editing`() {
        val config = config("event", listOf(AgentPartyMemberPlan(2, 504500, AgentEquipmentAction.Equip, 9400010, true))) { "" }
        val party = automationConfigurations(listOf(config), { it.server }, { "JP" })
            .getValue("configs").jsonArray.single().jsonObject.getValue("agentParty").jsonObject
        assertEquals(15, party.getValue("preset").jsonPrimitive.int)
        assertFalse(party.getValue("inGameEditingSupported").jsonPrimitive.boolean)
        val equip = party.getValue("lineup").jsonArray.single().jsonObject.getValue("equipment").jsonObject
        assertEquals(9400010, equip.getValue("craftEssenceId").jsonPrimitive.int)
        assertEquals("EventBonusFirst", equip.getValue("filterPolicy").jsonPrimitive.content)
        assertTrue(equip.getValue("requireMaxLimitBreak").jsonPrimitive.boolean)
    }

    @Test fun `real runner dispatch config access does not read unused skill commands`() {
        var commandReads=0
        val selected=config("selected") { commandReads++;error("unused command") }
        val configs=listOf(config("other") { error("also unused") },selected)
        assertSame(selected,automationConfig(configs,"selected"))
        assertEquals(0,commandReads)
        assertEquals("UnknownConfig",assertFailsWith<BattleRunValidationException>{automationConfig(configs,"absent")}.code)
        assertEquals(0,commandReads)
    }
}
