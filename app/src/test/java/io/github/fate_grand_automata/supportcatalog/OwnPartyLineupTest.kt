package io.github.fate_grand_automata.supportcatalog

import io.github.fate_grand_automata.prefs.core.OwnPartyLineup
import io.github.fate_grand_automata.prefs.core.OwnPartyMember
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OwnPartyLineupTest {
    private val caster = OwnPartyMember(504500, mapOf("CN" to "阿尔托莉雅·卡斯特", "NA" to "Altria Caster"))
    private val berserker = OwnPartyMember(704700, caster.names)

    @Test
    fun `own positions stay ordered and preserve different IDs with the same label`() {
        val lineup = OwnPartyLineup().withMember(1, caster).withMember(6, berserker)
        val restored = Json.decodeFromString<OwnPartyLineup>(Json.encodeToString(lineup))
        assertEquals(lineup, restored)
        assertEquals(504500, restored.slots[1]?.id)
        assertEquals(704700, restored.slots[6]?.id)
        assertNull(restored.slots[3])
    }

    @Test
    fun `renaming does not change the ID and duplicate IDs cannot occupy two positions`() {
        val start = OwnPartyLineup().withMember(2, caster)
        val renamed = caster.copy(names = caster.names + ("CN" to "术呆"))
        assertEquals(start, start.withMember(3, renamed))
        val changed = start.withMember(2, renamed)
        assertEquals(setOf(caster.id), changed.otherIds(4))
        assertEquals(emptySet(), changed.otherIds(2))
        assertEquals("术呆", changed.slots[2]?.label("CN"))
        assertEquals("Altria Caster", changed.slots[2]?.label("NA"))
        assertEquals(start.slots[2]?.id, changed.slots[2]?.id)
        assertEquals(OwnPartyLineup(), changed.withMember(2, null))
    }

    @Test
    fun `invalid party positions and collection placeholders are not accepted`() {
        assertFailsWith<IllegalArgumentException> { OwnPartyLineup().withMember(7, caster) }
        assertFailsWith<IllegalArgumentException> { OwnPartyMember(0, caster.names) }
        assertFailsWith<IllegalArgumentException> { OwnPartyLineup(mapOf(2 to caster, 3 to caster)) }
    }
}
