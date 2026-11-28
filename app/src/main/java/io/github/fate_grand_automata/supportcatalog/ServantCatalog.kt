package io.github.fate_grand_automata.supportcatalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private val catalogJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class CatalogServant(
    val id: Int,
    val collectionNo: Int,
    val className: String,
    val names: Map<String, String>,
    val aliases: Set<String> = emptySet()
) {
    val key get() = "atlas:svt:$id"

    fun label(region: String): String =
        names[region] ?: names["NA"] ?: names.getValue("JP")

    fun matches(query: String): Boolean =
        query.isBlank() || (names.values + aliases).any { it.contains(query, ignoreCase = true) } ||
            query == id.toString() || query == collectionNo.toString()

    companion object {
        private val keyPattern = Regex("atlas:svt:([1-9][0-9]*)")
        fun idFromKey(key: String) = keyPattern.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
    }
}

@Serializable
private data class BasicServant(
    val id: Int,
    val collectionNo: Int,
    val name: String,
    val originalName: String = name,
    val className: String = "",
    val type: String
)

internal enum class CatalogKind(val export: String, val translations: String, val types: Set<String>) {
    Servant("servant", "svt_names", setOf("normal", "heroine")),
    CraftEssence("equip", "ce_names", setOf("servantEquip"))
}

internal object ServantCatalog {
    private const val ATLAS = "https://api.atlasacademy.io"
    private const val CHALDEA = "https://raw.githubusercontent.com/chaldea-center/chaldea-data/main/mappings"

    // Opening one picker per team position should not redownload the same public index.
    private data class Snapshot(val entries: List<CatalogServant>, val expiresAtNanos: Long)
    private val cached = mutableMapOf<CatalogKind, Snapshot>()

    @Synchronized
    fun fetch(kind: CatalogKind = CatalogKind.Servant): List<CatalogServant> {
        cached[kind]?.takeIf { System.nanoTime() - it.expiresAtNanos < 0L }?.let { return it.entries }
        val jp = getRequired("$ATLAS/export/JP/basic_${kind.export}.json")
        val cn = optional { getRequired("$ATLAS/export/CN/basic_${kind.export}.json") }
        val translated = optional { getRequired("$CHALDEA/${kind.translations}.json") }
        return parse(jp, cn, translated, kind).also {
            cached[kind] = Snapshot(it, System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10))
        }
    }

    fun parse(
        jp: String,
        cn: String?,
        translations: String?,
        kind: CatalogKind = CatalogKind.Servant
    ): List<CatalogServant> {
        val chinese = cn?.let { catalogJson.decodeFromString<List<BasicServant>>(it) }
            .orEmpty().associateBy { it.id }
        val names = translations?.let {
            catalogJson.decodeFromString<Map<String, Map<String, String?>>>(it)
        }.orEmpty()
        return catalogJson.decodeFromString<List<BasicServant>>(jp)
            .asSequence()
            .filter { it.id > 0 && it.collectionNo > 0 && it.type in kind.types }
            .distinctBy { it.id }
            .map { servant ->
                val translationsForServant = names[servant.originalName] ?: names[servant.name].orEmpty()
                val localized = buildMap {
                    put("JP", servant.name)
                    for ((region, name) in translationsForServant) {
                        if (region != "JP" && !name.isNullOrBlank()) put(region, name)
                    }
                    // Region-specific records are matched by game ID, never by localized name.
                    chinese[servant.id]?.name?.takeIf(String::isNotBlank)?.let { put("CN", it) }
                }
                val aliases = (translationsForServant.values.filterNotNull() + servant.originalName)
                    .filter(String::isNotBlank).toSet() - localized.values.toSet()
                CatalogServant(servant.id, servant.collectionNo, servant.className, localized, aliases)
            }
            .sortedBy { it.collectionNo }
            .toList()
            .also { require(it.isNotEmpty()) { "Atlas returned an empty servant catalog" } }
    }

    fun faceUrls(id: Int): List<String> {
        require(id > 0)
        return parseFaceUrls(id, getRequired("$ATLAS/nice/JP/servant/$id"))
    }

    data class PartyArtUrls(val faces: List<String>, val figures: List<String>)

    fun partyArtUrls(id: Int): PartyArtUrls {
        require(id > 0)
        return parsePartyArtUrls(id, getRequired("$ATLAS/nice/JP/servant/$id"))
    }

    @Serializable
    private data class NiceServant(val id: Int, val extraAssets: ExtraAssets)

    @Serializable
    private data class ExtraAssets(
        val faces: Map<String, Map<String, String>?>,
        val facesChange: Map<String, Map<String, String>?> = emptyMap(),
        val narrowFigureChange: Map<String, Map<String, String>?> = emptyMap(),
        val narrowFigure: Map<String, Map<String, String>?> = emptyMap(),
        val equipFace: Map<String, Map<String, String>> = emptyMap()
    )

    fun equipFaces(id: Int): Pair<ByteArray, ByteArray> {
        require(id > 0)
        val result = catalogJson.decodeFromString<NiceServant>(getRequired("$ATLAS/nice/JP/equip/$id"))
        require(result.id == id)
        val url = result.extraAssets.faces["equip"]?.get(id.toString())
            ?: throw IOException("No catalog face for CE $id")
        val wide = result.extraAssets.equipFace["equip"]?.get(id.toString())
            ?: throw IOException("No catalog banner for CE $id")
        require(wide.matches(Regex("https://static\\.atlasacademy\\.io/JP/EquipFaces/[A-Za-z0-9_@.-]+\\.png")))
        return (face(url) ?: throw IOException("Craft Essence face unavailable")) to
            (fetchBytes(wide, 1024 * 1024) ?: throw IOException("Craft Essence banner unavailable"))
    }

    fun parseFaceUrls(id: Int, body: String): List<String> {
        val result = catalogJson.decodeFromString<NiceServant>(body)
        require(result.id == id) { "Atlas returned a different servant" }
        return validatedFaces(result.extraAssets)
    }

    // Groups may share stage keys but point at different art. Deduplicate by URL only.
    private fun variantUrls(base: Map<String, Map<String, String>?>, changes: Map<String, Map<String, String>?>) =
        (base.values + changes.values).filterNotNull().flatMap { it.values }.distinct()

    private fun validatedFaces(assets: ExtraAssets) =
        variantUrls(assets.faces, assets.facesChange).onEach {
            require(isFaceUrl(it)) { "Unexpected Atlas face URL" }
        }.also {
            require(it.isNotEmpty() && it.size <= 96) { "Unexpected number of Atlas faces" }
        }

    fun parsePartyArtUrls(id: Int, body: String): PartyArtUrls {
        val result = catalogJson.decodeFromString<NiceServant>(body)
        require(result.id == id) { "Atlas returned a different servant" }
        val figures = variantUrls(result.extraAssets.narrowFigure, result.extraAssets.narrowFigureChange)
        require(figures.size <= 96 && figures.all { figureUrl.matches(it) }) { "Unexpected Atlas formation art" }
        return PartyArtUrls(validatedFaces(result.extraAssets), figures)
    }

    private val figureUrl = Regex("""https://static\.atlasacademy\.io/JP/NarrowFigure/[0-9]+/[A-Za-z0-9_@.-]+\.png""")

    fun formationFigure(url: String): ByteArray? {
        require(figureUrl.matches(url))
        return fetchBytes(url, 1024 * 1024)
    }

    private fun isFaceUrl(url: String): Boolean = url.matches(
        Regex("https://static\\.atlasacademy\\.io/(JP|NA|CN|TW|KR)/Faces/[A-Za-z0-9_@.-]+\\.png")
    )

    fun face(url: String): ByteArray? {
        require(isFaceUrl(url))
        return fetchBytes(url, 1024 * 1024)
    }

    private fun getRequired(url: String): String =
        fetchBytes(url, 8 * 1024 * 1024)?.decodeToString() ?: throw IOException("Catalog unavailable")

    private fun fetchBytes(url: String, limit: Int): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            return when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > limit) throw IOException("Catalog response too large")
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("Catalog request failed: HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    }
}
