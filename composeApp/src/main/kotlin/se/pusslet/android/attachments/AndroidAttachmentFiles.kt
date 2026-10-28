package se.pusslet.android.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.pusslet.data.repository.AttachmentFiles
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The private folder (`files/attachments`) with shared pictures. Imports are re-encoded as JPEG at most
 * [MAX_SIDE] px with the camera's rotation applied, plus a small thumbnail – the original never leaves its app.
 * Excluded from cloud backup (backup rules).
 */
class AndroidAttachmentFiles(private val context: Context) : AttachmentFiles {
    private val log = Logger.withTag("Attachments")
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** Copies a shared picture in; returns its file name, or null if it could not be read as an image. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap = decode(uri, MAX_SIDE) ?: return@withContext null
            val name = "${Uuid.random()}.jpg"
            write(File(dir, name), bitmap)
            write(File(dir, thumbName(name)), scaled(bitmap, THUMB_SIDE))
            name
        } catch (e: IOException) {
            log.w { "Import failed: ${e::class.simpleName}" }
            null
        } catch (e: SecurityException) {
            log.w { "Import not allowed: ${e::class.simpleName}" }
            null
        }
    }

    /** Decodes a stored picture for display (off the main thread). */
    suspend fun load(fileName: String, thumbnail: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(dir, if (thumbnail) thumbName(fileName) else fileName).takeIf { it.isFile }
            ?: File(dir, fileName).takeIf { it.isFile }
            ?: return@withContext null
        file.inputStream().use { BitmapFactory.decodeStream(it) }
    }

    /** A content:// link other apps (the phone's picture viewer) may read once. */
    fun shareableUri(fileName: String): Uri? {
        val file = File(dir, fileName).takeIf { it.isFile } ?: return null
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) {
            listOf(File(dir, fileName), File(dir, thumbName(fileName))).forEach { file ->
                if (file.exists() && !file.delete()) log.w { "A picture could not be deleted" }
            }
        }
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) { if (!dir.deleteRecursively()) log.w { "Some pictures could not be deleted" } }
    }

    override suspend fun list(): Map<String, Instant> = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.startsWith(THUMB_PREFIX) }
            .associate { it.name to Instant.fromEpochMilliseconds(it.lastModified()) }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds the decoder only fills in the size (and returns null).
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val upright = if (rotation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        return scaled(upright, maxSide)
    }

    private fun scaled(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val factor = maxSide.toFloat() / longest
        return bitmap.scale((bitmap.width * factor).toInt(), (bitmap.height * factor).toInt())
    }

    private fun write(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
    }

    private fun thumbName(fileName: String) = "$THUMB_PREFIX$fileName"

    companion object {
        const val DIR = "attachments"
        private const val THUMB_PREFIX = "thumb_"
        private const val MAX_SIDE = 2048
        private const val THUMB_SIDE = 480
        private const val QUALITY = 85
    }
}
