package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotesTest {

    @Test
    fun parsesFourCategoriesInFixedOrderAndHidesEmptyOnes() {
        val body = """
            ## 修复

            - 修复下载失败后无法续传

            ## 新增

            - 支持断点续传

            ## 优化

            - 通知栏显示百分比
        """.trimIndent()

        val sections = UpdateNotes.parse(body)

        assertEquals(listOf("新增", "优化", "修复"), sections.map { it.title })
        assertEquals(listOf("支持断点续传"), sections[0].items)
    }

    @Test
    fun dropsCIAndDeveloperDetailsFromRemoteReleaseBody() {
        val body = """
            ## 发布策略

            - 本次由 GitHub Actions 编译，JVM 测试与 Android Lint 全部通过

            ## 新增

            - 更新下载支持 GitHub 与备用源自动切换

            ## 其他

            - SHA-256 校验值写入发布说明
            - 版本 0.6.1 / versionCode 24，包名与签名不变
        """.trimIndent()

        val sections = UpdateNotes.parse(body)

        assertEquals(listOf("新增", "其他"), sections.map { it.title })
        assertEquals(listOf("更新下载支持 GitHub 与备用源自动切换"), sections[0].items)
        assertEquals(listOf("版本 0.6.1 / versionCode 24，包名与签名不变"), sections[1].items)
    }

    @Test
    fun pureTechnicalBodyYieldsNoSectionsInsteadOfAWallOfText() {
        val body = """
            ## 发布策略

            - GitHub Actions 构建 universal APK，JVM 测试与 Lint 通过

            ## CI

            - README 同步脚本已更新，SHA-256 由 apksigner 校验
        """.trimIndent()

        assertTrue(UpdateNotes.parse(body).isEmpty())
        assertEquals("", UpdateNotes.userFacing(body))
    }

    @Test
    fun looseBulletsWithoutHeadingsFallIntoOther() {
        val body = """
            - 下载失败后自动重试三次
            - 通知栏可取消下载
        """.trimIndent()

        val sections = UpdateNotes.parse(body)

        assertEquals("其他", sections.single().title)
        assertEquals(2, sections.single().items.size)
    }

    @Test
    fun summaryKeepsAtMostThreeItems() {
        val body = """
            ## 新增

            - 一
            - 二

            ## 优化

            - 三
            - 四
        """.trimIndent()

        val summary = UpdateNotes.summary(body)

        assertTrue(summary.contains("一"))
        assertTrue(summary.contains("三"))
        assertTrue(!summary.contains("四"))
    }
}

class UpdateTransferPolicyTest {

    @Test
    fun parsesValidPartialContent() {
        val range = UpdateTransferPolicy.parseContentRange("bytes 1024-2047/4096")

        assertEquals(1024L, range?.start)
        assertEquals(2047L, range?.end)
        assertEquals(4096L, range?.total)
    }

    @Test
    fun acceptsUnknownTotalSize() {
        val range = UpdateTransferPolicy.parseContentRange("bytes 0-99/*")

        assertEquals(0L, range?.start)
        assertNull(range?.total)
    }

    @Test
    fun rejectsMalformedOrInvertedRanges() {
        assertNull(UpdateTransferPolicy.parseContentRange(null))
        assertNull(UpdateTransferPolicy.parseContentRange("bytes */4096"))
        assertNull(UpdateTransferPolicy.parseContentRange("bytes 10-5/100"))
        assertNull(UpdateTransferPolicy.parseContentRange("items 0-5/100"))
    }

    @Test
    fun resumeStartMustMatchLocalSize() {
        val range = UpdateTransferPolicy.parseContentRange("bytes 100-199/200")

        assertEquals(100L, UpdateTransferPolicy.resumeStart(range, 100L))
        assertNull(UpdateTransferPolicy.resumeStart(range, 50L))
        assertNull(UpdateTransferPolicy.resumeStart(null, 100L))
    }

    @Test
    fun retryPolicyDistinguishesTransientAndPermanentFailures() {
        assertTrue(UpdateTransferPolicy.isRetryable(500))
        assertTrue(UpdateTransferPolicy.isRetryable(503))
        assertTrue(UpdateTransferPolicy.isRetryable(429))
        assertTrue(UpdateTransferPolicy.isRetryable(408))
        assertTrue(!UpdateTransferPolicy.isRetryable(404))
        assertTrue(!UpdateTransferPolicy.isRetryable(403))
        assertTrue(!UpdateTransferPolicy.isRetryable(200))
    }

    @Test
    fun backoffIsBounded() {
        assertEquals(1000L, UpdateTransferPolicy.retryDelayMillis(0))
        assertEquals(2000L, UpdateTransferPolicy.retryDelayMillis(1))
        assertEquals(4000L, UpdateTransferPolicy.retryDelayMillis(2))
        assertEquals(4000L, UpdateTransferPolicy.retryDelayMillis(9))
    }
}
