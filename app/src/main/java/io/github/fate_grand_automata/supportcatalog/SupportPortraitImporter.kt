package io.github.fate_grand_automata.supportcatalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

internal object SupportPortraitImporter {
    suspend fun import(servant: CatalogServant, store: SupportServantStore) {
        val seen = mutableSetOf<List<Byte>>()
        val portraits = ServantCatalog.faceUrls(servant.id).mapNotNull { url ->
            currentCoroutineContext().ensureActive()
            val bytes = ServantCatalog.face(url) ?: return@mapNotNull null
            val png = render(bytes)
            png.takeIf { seen.add(MessageDigest.getInstance("SHA-256").digest(png).toList()) }
        }
        if (portraits.isEmpty()) throw IOException("No usable servant portraits")
        currentCoroutineContext().ensureActive()
        store.replace(servant, portraits)
    }

    private fun render(encoded: ByteArray): ByteArray {
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, header)
        require(header.outWidth in 1..512 && header.outHeight in 1..512)
        val face = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            ?: throw IOException("Atlas portrait could not be decoded")
        try {
            // At the 720p matching scale, the support list exposes this window of the face.
            // Geometry measured independently upstream in Fate-Grand-Automata/FGA#2308.
            val scaled = Bitmap.createScaledBitmap(face, 157, 157, true)
            try {
                val crop = Bitmap.createBitmap(scaled, 3, 47, 125, 44)
                try {
                    return ByteArrayOutputStream().use {
                        if (!crop.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                            throw IOException("Support portrait could not be encoded")
                        }
                        it.toByteArray()
                    }
                } finally {
                    crop.recycle()
                }
            } finally {
                if (scaled !== face) scaled.recycle()
            }
        } finally {
            face.recycle()
        }
    }
}
