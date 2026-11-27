package io.github.fate_grand_automata.supportcatalog

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** App-local, independent of the user-maintained Support Image Maker folder. */
internal class SupportServantStore(private val directory: File) {
    private fun archive(id: Int) = File(directory, "$id.zip")

    fun replace(servant: CatalogServant, portraits: List<ByteArray>) {
        require(servant.id > 0 && portraits.isNotEmpty())
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the catalog image directory")
        }
        val staging = File.createTempFile("servant-", ".tmp", directory)
        try {
            staging.outputStream().buffered().use { stream ->
                ZipOutputStream(stream).use { zip ->
                    zip.putNextEntry(ZipEntry("servant.json"))
                    zip.write(Json.encodeToString(servant).encodeToByteArray())
                    zip.closeEntry()
                    portraits.forEachIndexed { index, png ->
                        zip.putNextEntry(ZipEntry("face-$index.png"))
                        zip.write(png)
                        zip.closeEntry()
                    }
                }
            }
            // Same-directory rename keeps the previous pack intact if the import fails.
            if (!staging.renameTo(archive(servant.id))) throw IOException("Could not save portraits")
        } finally {
            staging.delete()
        }
    }

    fun read(id: Int): List<ByteArray> = try {
        ZipFile(archive(id)).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".png") }.map { entry ->
                zip.getInputStream(entry).use { it.readBytes() }
            }.toList()
        }
    } catch (_: IOException) {
        emptyList()
    }

    private fun servant(id: Int): CatalogServant? = runCatching {
        ZipFile(archive(id)).use { zip ->
            val entry = zip.getEntry("servant.json") ?: return@use null
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            Json.decodeFromString<CatalogServant>(json).takeIf { it.id == id }
        }
    }.getOrNull()

    fun label(key: String, region: String): String? =
        CatalogServant.idFromKey(key)?.let(::servant)?.label(region)

    fun names(region: String): Map<String, String> = directory.listFiles().orEmpty()
        .asSequence()
        .filter { it.extension == "zip" }
        .mapNotNull { it.nameWithoutExtension.toIntOrNull()?.let(::servant) }
        .sortedBy { it.collectionNo }
        .associate { it.key to it.label(region) }

    companion object {
        fun inApp(filesDir: File) = SupportServantStore(File(filesDir, "support-servant-catalog"))

        fun region(locale: Locale): String = when (locale.language) {
            "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") "TW" else "CN"
            "ja" -> "JP"
            "ko" -> "KR"
            else -> "NA"
        }
    }
}
