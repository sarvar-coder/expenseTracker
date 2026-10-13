// ponytail: platform ExifInterface (API 24+ stream ctor), not the androidx dep; add it if rotations go wrong on old phones.
@file:Suppress("ExifInterface")

package com.sarvarbek.expense_tracker.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sarvarbek.expense_tracker.data.ExpenseDao
import com.sarvarbek.expense_tracker.data.Need
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Kerakli photos: private bucket `need-photos`, object `{needId}/photo.jpg`.
 * A picked photo waits in filesDir until its need row is on the server, then
 * [upload] sends it and sets photo_path. Downloads are cached in cacheDir.
 * ponytail: no image library; add Coil if thumbnails get slow.
 */
class NeedPhotos(context: Context, private val client: SupabaseClient) {
    private val resolver = context.contentResolver
    private val pendingDir = File(context.filesDir, "need-photos")
    private val cacheDir = File(context.cacheDir, "need-photos")

    /** The picked image, upright, at most [MAX_PX] on its long side, JPEG q80. Null = unreadable. */
    suspend fun encode(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
            var bmp = resolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }!!
            val scale = MAX_PX.toFloat() / max(bmp.width, bmp.height)
            val degrees = resolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
            if (scale < 1f || degrees != 0f) {
                val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(degrees) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "encode failed: $e")
            null
        }
    }

    /** Keeps [jpeg] for [needId] until [upload] sends it. Call after the need row is inserted. */
    suspend fun stash(needId: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        pendingDir.mkdirs()
        // Temp name first: upload skips it, so a crash mid-write can't send a truncated photo.
        File(pendingDir, "$needId.tmp").apply { writeBytes(jpeg) }.renameTo(pending(needId))
    }

    fun hasPhoto(n: Need) = n.photoPath != null || pending(n.id).exists()

    /** The photo scaled down to about [maxPx] wide; downloads (and caches) it first if needed. Throws when offline. */
    suspend fun load(n: Need, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = pending(n.id).takeIf { it.exists() } ?: n.photoPath?.let { path ->
            cached(path).also { f ->
                if (!f.exists()) {
                    val bytes = client.storage.from(BUCKET).downloadAuthenticated(path)
                    cacheDir.mkdirs()
                    val tmp = File(cacheDir, "${f.name}.tmp").apply { writeBytes(bytes) }
                    tmp.renameTo(f)
                }
            }
        } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    /**
     * Sends stashed photos whose need is on the server (clean) and sets their
     * photo_path. A failure keeps the file for the next sync. True = a row changed.
     */
    suspend fun upload(db: ExpenseDao): Boolean {
        var changed = false
        for (f in pendingDir.listFiles { f -> f.extension == "jpg" }.orEmpty()) {
            val n = db.getNeed(f.nameWithoutExtension)
            if (n == null || n.deletedAt != null) { f.delete(); continue } // deleted, or another account's
            if (n.dirty) continue // row not pushed yet (or refused)
            val path = "${n.id}/photo.jpg"
            try {
                client.storage.from(BUCKET).upload(path, f.readBytes()) { upsert = true }
                db.setNeedPhoto(n.id, path)
                cacheDir.mkdirs()
                f.renameTo(cached(path))
                changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "upload ${n.id} failed: $e")
            }
        }
        return changed
    }

    private fun pending(needId: String) = File(pendingDir, "$needId.jpg")
    private fun cached(path: String) = File(cacheDir, path.replace('/', '_'))

    companion object {
        private const val TAG = "photos"
        const val BUCKET = "need-photos"
        const val MAX_PX = 1280
    }
}
