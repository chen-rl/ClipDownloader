package com.clipdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 下载记录按文件裁剪（文件被覆盖、删除后同步记录）的单元测试 */
class DownloadRecordTest {

    private val t = 1_794_851_452_000L

    private fun record(vararg paths: String): DownloadRecord {
        val list = paths.toList()
        return DownloadRecord(
            batchId = 1, platform = Platform.DOUYIN, title = "nie11", success = true,
            filePath = list.first(), fileName = list.first().substringAfterLast('/'),
            filePaths = list,
            coverUrls = list.map { "https://cdn/cover$it" },
            kinds = list.map { if (it.endsWith(".jpg")) "图片" else "视频" },
            durations = list.map { 1000L },
            sizes = list.map { 10L },
            itemSkipped = list.map { false },
            itemErrors = list.map { null },
            publishTimes = list.map { t },
            itemCount = list.size, okCount = list.size, skippedCount = 0, fileSize = list.size * 10L
        )
    }

    @Test
    fun withoutPaths_dropsOnlyTheGivenFilesAndKeepsListsAligned() {
        val rec = record("/d/A.mp4", "/d/B.jpg", "/d/C.mp4")
        val trimmed = rec.withoutPaths(setOf("/d/B.jpg"))!!

        assertEquals(listOf("/d/A.mp4", "/d/C.mp4"), trimmed.filePaths)
        assertEquals(listOf("https://cdn/cover/d/A.mp4", "https://cdn/cover/d/C.mp4"), trimmed.coverUrls)
        assertEquals(listOf("视频", "视频"), trimmed.kinds)
        assertEquals(listOf(1000L, 1000L), trimmed.durations)
        assertEquals(listOf(t, t), trimmed.publishTimes)
        assertEquals(listOf(10L, 10L), trimmed.sizes)
        assertEquals(listOf(false, false), trimmed.itemSkipped)
        assertEquals(2, trimmed.itemCount)
        assertEquals(2, trimmed.okCount)
        assertEquals(0, trimmed.skippedCount)
        // 首个条目换成剩下里的第一个
        assertEquals("/d/A.mp4", trimmed.filePath)
        assertEquals("A.mp4", trimmed.fileName)
        assertEquals("https://cdn/cover/d/A.mp4", trimmed.coverUrl)
    }

    @Test
    fun withoutPaths_recomputesFirstItemWhenTheFirstFileIsDropped() {
        val rec = record("/d/A.mp4", "/d/B.jpg", "")
        val trimmed = rec.withoutPaths(setOf("/d/A.mp4"))!!

        assertEquals(listOf("/d/B.jpg", ""), trimmed.filePaths)
        assertEquals("/d/B.jpg", trimmed.filePath)
        assertEquals("图片", trimmed.kind)
        assertEquals("https://cdn/cover/d/B.jpg", trimmed.coverUrl)
        // 剩下 B 和那个失败条目
        assertEquals(2, trimmed.itemCount)
        // 真正落盘的只有 B：空路径（失败条目）不算成功
        assertEquals(1, trimmed.okCount)
    }

    @Test
    fun withoutPaths_returnsNullWhenNoFileItemRemains() {
        val rec = record("/d/A.mp4", "/d/B.jpg", "")
        assertNull(rec.withoutPaths(setOf("/d/A.mp4", "/d/B.jpg")))
    }

    @Test
    fun withoutPaths_ignoresUnrelatedAndBlankPaths() {
        val rec = record("/d/A.mp4", "")
        assertSame(rec, rec.withoutPaths(setOf("/d/other.mp4")))
        // 失败条目的空路径不算文件：只点名空路径时记录原样
        assertSame(rec, rec.withoutPaths(setOf("")))
    }
}
