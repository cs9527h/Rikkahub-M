package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FileEncoderExifTransformTest {

    @Test
    fun `all supported exif orientations should map to expected transform`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_NORMAL))
        assertEquals(ExifTransformType.FLIP_HORIZONTAL, mapExifOrientationToTransform(ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(ExifTransformType.ROTATE_180, mapExifOrientationToTransform(ORIENTATION_ROTATE_180))
        assertEquals(ExifTransformType.FLIP_VERTICAL, mapExifOrientationToTransform(ORIENTATION_FLIP_VERTICAL))
        assertEquals(ExifTransformType.TRANSPOSE, mapExifOrientationToTransform(ORIENTATION_TRANSPOSE))
        assertEquals(ExifTransformType.ROTATE_90, mapExifOrientationToTransform(ORIENTATION_ROTATE_90))
        assertEquals(ExifTransformType.TRANSVERSE, mapExifOrientationToTransform(ORIENTATION_TRANSVERSE))
        assertEquals(ExifTransformType.ROTATE_270, mapExifOrientationToTransform(ORIENTATION_ROTATE_270))
    }

    @Test
    fun `undefined or unknown orientation should map to none`() {
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(ORIENTATION_UNDEFINED))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(999))
        assertEquals(ExifTransformType.NONE, mapExifOrientationToTransform(-1))
    }

    @Test
    fun `image budget should be four megapixels with ten thousand long edge`() {
        assertEquals(4_000_000L, MAX_IMAGE_PIXELS)
        assertEquals(10_000, MAX_IMAGE_DIMENSION)
        // 12MP 相机原图必须真的被压到这个预算内，而不是只有常量改小了
        val encoded = encodedSize(4000, 3000)
        assertWithinBudget(encoded)
        assertTrue("$encoded 没有被压缩", encoded.pixelCount() < 4_000_000L)
    }

    @Test
    fun `landscape photos should be scaled down to pixel budget`() {
        val target = targetOf(4000, 3000)

        assertEquals(ImageSize(2309, 1732), target)
        assertWithinBudget(target)
        assertAspectKept(4000, 3000, target)
        // 只按需缩小，不会因为 2 的幂次采样缩过头
        assertTrue(target.pixelCount() > 3_900_000L)
        // 采样解码后再精确缩放，最终尺寸就是目标尺寸
        assertEquals(target, encodedSize(4000, 3000))
    }

    @Test
    fun `portrait photos should be scaled down to pixel budget`() {
        val target = targetOf(3000, 4000)

        assertEquals(ImageSize(1732, 2309), target)
        assertWithinBudget(target)
        assertAspectKept(3000, 4000, target)
        assertTrue(target.pixelCount() > 3_900_000L)
        assertEquals(target, encodedSize(3000, 4000))
        assertTrue("竖图长边在高度上", target.height > target.width)
    }

    @Test
    fun `images already inside the budget should keep their original size`() {
        val untouched = listOf(
            800 to 600,
            1080 to 2400,
            1272 to 2800, // 长截图：3.56MP，宽高都不动
            9000 to 100, // 超宽全景：长边 9000 < 10000
            1 to 1
        )

        for ((width, height) in untouched) {
            val target = targetOf(width, height)
            assertEquals("$width x $height 不该被缩放", ImageSize(width, height), target)
            assertEquals("$width x $height 不该重采样解码", 1, sampleSizeOf(width, height))
            assertEquals(ImageSize(width, height), encodedSize(width, height))
        }
    }

    @Test
    fun `very long screenshots should be capped by long edge without cropping`() {
        // 1200x30000 的整页截图：像素预算和长边限制同时触发，等比缩到长边 10000
        val tall = targetOf(1200, 30000)
        assertEquals(ImageSize(400, 10000), tall)
        assertEquals(10_000, maxOf(tall.width, tall.height))
        assertAspectKept(1200, 30000, tall)
        assertEquals(tall, encodedSize(1200, 30000))

        // 细长图：宽度只按比例缩小，不会被裁成 1 像素，内容完整保留
        val veryThin = targetOf(100, 20000)
        assertEquals(ImageSize(50, 10000), veryThin)
        assertEquals(10000, veryThin.height)
        assertTrue("宽度不该被裁切", veryThin.width > 1)
        assertAspectKept(100, 20000, veryThin)
        assertEquals(veryThin, encodedSize(100, 20000))

        // 反向的超宽长图同样只缩长边，另一条边仍按比例保留
        val veryWide = targetOf(20000, 100)
        assertEquals(ImageSize(10000, 50), veryWide)
        assertEquals(10_000, veryWide.width)
        assertEquals(50, veryWide.height)
        assertAspectKept(20000, 100, veryWide)
        assertEquals(veryWide, encodedSize(20000, 100))

        for (size in listOf(tall, veryThin, veryWide)) {
            assertWithinBudget(size)
        }
    }

    @Test
    fun `huge square images should be limited by max dimension`() {
        val target = targetOf(20000, 20000)

        assertEquals(ImageSize(2000, 2000), target)
        assertEquals(4_000_000L, target.pixelCount())
        assertWithinBudget(target)
    }

    @Test
    fun `sample size should never undershoot the target size`() {
        val sources = listOf(
            4000 to 3000,
            3000 to 4000,
            8000 to 6000,
            12000 to 9000,
            20000 to 20000,
            1272 to 2800,
            1200 to 30000,
            20000 to 100,
            4032 to 3024,
            1440 to 3120
        )

        for ((width, height) in sources) {
            val target = targetOf(width, height)
            val sampleSize = sampleSizeOf(width, height)
            val decodedWidth = width / sampleSize
            val decodedHeight = height / sampleSize

            // 采样只用来省解码内存：解码结果不能小于目标，否则就缩过头了
            assertTrue(
                "$width x $height 采样 $sampleSize 后 $decodedWidth x $decodedHeight 小于目标 $target",
                decodedWidth >= target.width && decodedHeight >= target.height
            )
            // 再采样一级就会小于目标，说明采样率已经取到最大
            assertTrue("$width x $height 采样率 $sampleSize 不是 2 的幂次", isPowerOfTwo(sampleSize))
            val next = sampleSize * 2
            assertTrue(
                "$width x $height 还能再采样一级",
                width / next < target.width || height / next < target.height
            )
        }
    }

    @Test
    fun `every encoded image should fit the budget and keep its aspect ratio`() {
        val sources = listOf(
            320 to 240, // 小图标
            1080 to 2400, // 手机截图
            1440 to 3120, // 高分屏截图
            1272 to 2896, // 长截图
            1600 to 900, // 横屏截图
            4032 to 3024, // 12MP 相机
            8000 to 6000, // 48MP 相机
            12000 to 9000, // 108MP 相机
            20000 to 20000, // 巨图
            3000 to 30000, // 超长网页截图
            30000 to 3000, // 超宽全景
            1 to 1
        )

        for ((width, height) in sources) {
            val encoded = encodedSize(width, height)
            assertWithinBudget(encoded)
            assertAspectKept(width, height, encoded)
            assertTrue("$width x $height 缩放后出现 0 像素", encoded.width > 0 && encoded.height > 0)
            if (width.toLong() * height <= MAX_IMAGE_PIXELS && maxOf(width, height) <= MAX_IMAGE_DIMENSION) {
                assertEquals("$width x $height 在预算内不该被改动", ImageSize(width, height), encoded)
            }
        }
    }

    @Test
    fun `invalid dimensions should not be scaled`() {
        assertEquals(ImageSize(0, 100), targetOf(0, 100))
        assertEquals(ImageSize(100, 0), targetOf(100, 0))
        assertEquals(ImageSize(-5, -5), targetOf(-5, -5))
        assertEquals(1, sampleSizeOf(0, 100))
        assertEquals(1, sampleSizeOf(100, 0))
        assertEquals(1, calculateImageInSampleSize(4000, 3000, targetWidth = 0, targetHeight = 0))
    }

    /**
     * 复现 `FileEncoder.compressToJpeg` 的尺寸决策：先按 2 的幂次采样解码，再按需精确缩放到目标尺寸。
     *
     * ai 模块的单测跑在 JVM 上，测试类路径里没有 Robolectric，`android.graphics.Bitmap` 只有会抛异常的桩，
     * 所以这里按 `BitmapFactory` 的整数除法规格模拟解码尺寸，真正的缩放决策全部走生产函数
     * （[calculateImageTargetSize] / [calculateImageInSampleSize]）。
     */
    private fun encodedSize(width: Int, height: Int): ImageSize {
        val target = targetOf(width, height)
        val sampleSize = calculateImageInSampleSize(width, height, target.width, target.height)
        val decoded = ImageSize(
            width = (width / sampleSize).coerceAtLeast(1),
            height = (height / sampleSize).coerceAtLeast(1)
        )
        return if (decoded.width > target.width || decoded.height > target.height) target else decoded
    }

    private fun ImageSize.pixelCount(): Long = width.toLong() * height

    private fun targetOf(width: Int, height: Int): ImageSize =
        calculateImageTargetSize(width, height, MAX_IMAGE_DIMENSION, MAX_IMAGE_PIXELS)

    private fun sampleSizeOf(width: Int, height: Int): Int {
        val target = targetOf(width, height)
        return calculateImageInSampleSize(width, height, target.width, target.height)
    }

    private fun assertWithinBudget(size: ImageSize) {
        assertTrue(
            "${size.width}x${size.height} 超出像素预算 ${size.pixelCount()}",
            size.pixelCount() <= MAX_IMAGE_PIXELS
        )
        assertTrue(
            "长边 ${maxOf(size.width, size.height)} 超过 $MAX_IMAGE_DIMENSION",
            maxOf(size.width, size.height) <= MAX_IMAGE_DIMENSION
        )
    }

    private fun assertAspectKept(sourceWidth: Int, sourceHeight: Int, size: ImageSize) {
        val sourceRatio = sourceWidth.toDouble() / sourceHeight
        val scaledRatio = size.width.toDouble() / size.height
        assertTrue(
            "$sourceWidth x $sourceHeight 缩放成 ${size.width}x${size.height} 后宽高比变了：" +
                "$sourceRatio vs $scaledRatio",
            abs(sourceRatio - scaledRatio) / sourceRatio <= 0.01
        )
    }

    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    companion object {
        private const val ORIENTATION_UNDEFINED = 0
        private const val ORIENTATION_NORMAL = 1
        private const val ORIENTATION_FLIP_HORIZONTAL = 2
        private const val ORIENTATION_ROTATE_180 = 3
        private const val ORIENTATION_FLIP_VERTICAL = 4
        private const val ORIENTATION_TRANSPOSE = 5
        private const val ORIENTATION_ROTATE_90 = 6
        private const val ORIENTATION_TRANSVERSE = 7
        private const val ORIENTATION_ROTATE_270 = 8
    }
}
