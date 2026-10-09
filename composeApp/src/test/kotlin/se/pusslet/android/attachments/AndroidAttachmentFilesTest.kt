package se.pusslet.android.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = AndroidAttachmentFiles(context)
    private val folder = File(context.filesDir, AndroidAttachmentFiles.DIR)

    @After
    fun tearDown() {
        runBlocking { files.deleteAll() }
    }

    private fun photo(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(74, 104, 81)) }
        val file = File(context.cacheDir, "shared.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun aLargePhotoIsStoredSmallerWithAThumbnail() = runBlocking {
        val name = assertNotNull(files.import(photo(4000, 3000)))
        val stored = File(folder, name).inputStream().use { BitmapFactory.decodeStream(it) }
        assertEquals(2048, stored.width)
        assertEquals(1536, stored.height)
        val thumb = assertNotNull(files.load(name, thumbnail = true))
        assertEquals(480, maxOf(thumb.width, thumb.height))
        assertEquals(setOf(name), files.list().keys, "thumbnails are not listed as attachments")

        files.delete(name)
        assertTrue(folder.listFiles().orEmpty().isEmpty(), folder.listFiles().orEmpty().joinToString { it.name })
        assertNull(files.load(name, thumbnail = true))
    }

    @Test
    fun somethingThatIsNotAPictureIsRefused() = runBlocking {
        val text = File(context.cacheDir, "not-a-picture.jpg").apply { writeText("hej") }
        assertNull(files.import(Uri.fromFile(text)))
        assertTrue(files.list().isEmpty())
    }
}
