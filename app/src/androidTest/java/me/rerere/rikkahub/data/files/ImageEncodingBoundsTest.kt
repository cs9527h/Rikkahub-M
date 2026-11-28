package me.rerere.rikkahub.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Exercises the public production encoder and Android's actual JPEG/Bitmap codecs, not a size model. */
@RunWith(AndroidJUnit4::class)
class ImageEncodingBoundsTest {
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
            "image-bounds-",
        ).toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun landscapeJpegIsActuallyEncodedBelowFourMegapixels() {
        val (width, height) = encode(4000, 3000)
        assertTrue(width.toLong() * height <= 4_000_000L)
        assertTrue(width.toLong() * height > 3_900_000L)
        assertTrue(kotlin.math.abs(width.toDouble() / height - 4.0 / 3.0) < 0.01)
    }

    @Test fun portraitJpegIsActuallyEncodedBelowFourMegapixels() {
        val (width, height) = encode(3000, 4000)
        assertTrue(width.toLong() * height <= 4_000_000L)
        assertTrue(height > width)
        assertTrue(kotlin.math.abs(width.toDouble() / height - 3.0 / 4.0) < 0.01)
    }

    @Test fun smallJpegKeepsItsDimensions() {
        assertEquals(800 to 600, encode(800, 600))
    }

    @Test fun longScreenshotKeepsTheEntireAspectRatioWithoutCropping() {
        val (width, height) = encode(100, 20000)
        assertEquals(10000, height)
        assertEquals(50, width)
    }

    private fun encode(width: Int, height: Int): Pair<Int, Int> {
        val source = File(directory, "$width-$height.jpg")
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.CYAN)
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            input.recycle()
        }
        val encoded = UIMessagePart.Image(Uri.fromFile(source).toString()).encodeBase64(withPrefix = false).getOrThrow()
        assertEquals("image/jpeg", encoded.mimeType)
        val bytes = Base64.decode(encoded.base64, Base64.DEFAULT)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        assertTrue(dimensions.outWidth > 0 && dimensions.outHeight > 0)
        assertTrue(source.isFile)
        return dimensions.outWidth to dimensions.outHeight
    }
}
