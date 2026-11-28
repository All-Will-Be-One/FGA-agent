package io.github.fate_grand_automata.supportcatalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServantCatalogTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `craft essence catalog uses CE IDs and tolerates the absent class field`() {
        val jp = """[
          {"id":9400010,"collectionNo":1,"name":"頑強","type":"servantEquip"},
          {"id":504500,"collectionNo":284,"name":"術","className":"caster","type":"normal"}
        ]"""
        val cn = """[{"id":9400010,"collectionNo":1,"name":"顽强","type":"servantEquip"}]"""
        val ce = ServantCatalog.parse(jp, cn, null, CatalogKind.CraftEssence).single()
        assertEquals(9400010, ce.id)
        assertEquals("顽强", ce.label("CN"))
        assertEquals("", ce.className)
        assertTrue(ce.matches("顽强"))
    }

    @Test
    fun `regional labels and aliases never replace game IDs`() {
        val jp = """[
            {"id":300500,"collectionNo":18,"name":"エリザベート","className":"lancer","type":"normal"},
            {"id":602000,"collectionNo":61,"name":"エリザベート","className":"caster","type":"normal"},
            {"id":900999,"collectionNo":501,"name":"NPC","className":"lancer","type":"enemyCollectionDetail"},
            {"id":300500,"collectionNo":18,"name":"duplicate","className":"lancer","type":"normal"}
        ]"""
        val cn = """[
            {"id":300500,"collectionNo":18,"name":"伊丽莎白（枪）","className":"lancer","type":"normal"},
            {"id":602000,"collectionNo":61,"name":"伊丽莎白（术）","className":"caster","type":"normal"}
        ]"""
        val translations = """{"エリザベート":{"JP":null,"CN":"伊丽莎白","TW":"伊麗莎白"}}"""
        val servants = ServantCatalog.parse(jp, cn, translations)
        assertEquals(listOf("atlas:svt:300500", "atlas:svt:602000"), servants.map { it.key })
        assertEquals(listOf("伊丽莎白（枪）", "伊丽莎白（术）"), servants.map { it.label("CN") })
        assertEquals("伊麗莎白", servants[0].label("TW"))
        assertTrue(servants.all { it.matches("伊丽莎白") })
        assertTrue(servants[0].matches("18"))
        assertEquals(300500, CatalogServant.idFromKey(servants[0].key))
        assertNull(CatalogServant.idFromKey("Castoria"))
        assertNull(CatalogServant.idFromKey("atlas:svt:300500.png"))
    }

    @Test
    fun `portrait lookup rejects response for a different game ID and deduplicates face variants`() {
        val face = "https://static.atlasacademy.io/JP/Faces/f_5045000.png"
        val costume = "https://static.atlasacademy.io/JP/Faces/f_5045010.png"
        val nice = """{"id":504500,"extraAssets":{"faces":{
            "ascension":{"1":"$face","2":"$face"},"costume":{"11":"$costume"}
        }}}"""
        assertEquals(listOf(face, costume), ServantCatalog.parseFaceUrls(504500, nice))
        assertFailsWith<IllegalArgumentException> { ServantCatalog.parseFaceUrls(284, nice) }
        assertFailsWith<IllegalArgumentException> {
            ServantCatalog.parseFaceUrls(504500, nice.replace("static.atlasacademy.io", "example.com"))
        }
    }

    @Test
    fun `party art keeps final ascension and costume figures separate from picker faces`() {
        val face = "https://static.atlasacademy.io/JP/Faces/f_4030000.png"
        val first = "https://static.atlasacademy.io/JP/NarrowFigure/403000/403000@0.png"
        val last = first.replace("403000@0", "403000_2@0")
        val costume = first.replace("@0", "@10")
        val body = """{"id":403000,"extraAssets":{"faces":{"ascension":{"1":"$face"}},
            "narrowFigure":{"ascension":{"1":"$first","2":"$first","4":"$last"},"costume":{"10":"$costume"}}}}"""
        val art = ServantCatalog.parsePartyArtUrls(403000, body)
        assertEquals(listOf(face), art.faces)
        assertEquals(listOf(first, last, costume), art.figures)
        assertFailsWith<IllegalArgumentException> { ServantCatalog.parsePartyArtUrls(705500, body) }
        assertFailsWith<IllegalArgumentException> { ServantCatalog.parsePartyArtUrls(403000, body.replace(last, face)) }
        val legacy = """{"id":403000,"extraAssets":{"faces":{"ascension":{"1":"$face"}}}}"""
        assertTrue(ServantCatalog.parsePartyArtUrls(403000, legacy).figures.isEmpty())
    }

    @Test
    fun `reimport renames the display label without changing the selection or touching manual data`() {
        val store = SupportServantStore.inApp(directory)
        val manual = File(directory, "manual.png").apply { writeText("original") }
        val servant = CatalogServant(504500, 284, "caster", mapOf("JP" to "キャスター", "CN" to "术呆"))
        val key = servant.key
        store.replace(servant, listOf(byteArrayOf(1, 2)))
        assertEquals("术呆", store.label(key, "CN"))
        assertContentEquals(byteArrayOf(1, 2), store.read(504500).single())
        assertFailsWith<IllegalArgumentException> { store.replace(servant, emptyList()) }
        assertContentEquals(byteArrayOf(1, 2), store.read(504500).single())
        store.replace(servant.copy(names = mapOf("JP" to "キャスター", "CN" to "阿尔托莉雅")), listOf(byteArrayOf(3)))
        assertEquals(mapOf(key to "阿尔托莉雅"), store.names("CN"))
        assertContentEquals(byteArrayOf(3), store.read(504500).single())
        assertTrue(store.read(284).isEmpty())
        assertEquals("original", manual.readText())
        assertEquals("TW", SupportServantStore.region(Locale.forLanguageTag("zh-Hant")))
    }
}
