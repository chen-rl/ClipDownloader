package com.clipdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 作品库归并（下载记录 → 按作品）的单元测试。
 * 样本是核对过的真实数据：抖音博主 nie11 的主页批次，实况图作品《如花似梦是我们短暂的相逢》
 * （标识 06dee402ce，发布于 2026-11-17）与纯视频作品《人太多…》（标识 53ee36dc47，发布于 2026-03-26）。
 */
class LibraryWorksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val liveToken = "06dee402ce"
    private val videoToken = "53ee36dc47"
    private val root by lazy { tmp.newFolder("ClipDownloader").absolutePath }
    private val authorDir by lazy { File(root, "Douyin/nie11").apply { mkdirs() } }

    private fun touch(path: String, bytes: Int = 10): String =
        File(path).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }.absolutePath

    private fun batch(vararg paths: String, profile: Boolean = true) = DownloadRecord(
        batchId = 1, platform = Platform.DOUYIN,
        title = if (profile) "nie11" else "作品",
        filePath = paths.first(), fileName = File(paths.first()).name, filePaths = paths.toList(),
        success = true, isProfile = profile, itemCount = paths.size,
        coverUrls = paths.map { "" }, kinds = paths.map { "" }, durations = paths.map { 0L },
        sizes = paths.map { 0L }, publishTimes = paths.map { 0L }, okCount = paths.size
    )

    @Test
    fun build_groupsFilesByTokenAndPicksUpCoversFromDisk() {
        val liveDir = "2026-11-17_如花似梦是我们短暂的相逢_$liveToken"
        val videoDir = "2026-03-26_人太多大部分是漫无目的的走#夏天#蓝调#傍晚_$videoToken"
        val liveVideo = touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01.mp4", 300)
        touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01_cover.webp", 70)
        val video = touch("$authorDir/$videoDir/人太多大部分是漫无目的的走#夏天#蓝调#傍晚_$videoToken.mp4", 900)
        val cover = touch("$authorDir/$videoDir/人太多大部分是漫无目的的走#夏天#蓝调#傍晚_${videoToken}_01_cover.jpeg", 15)
        // 记录里只有条目主文件；封面是落盘时另存的，不在记录里
        val record = batch(liveVideo, video).copy(
            publishTimes = listOf(1_794_851_452_000L, 1_774_509_090_000L)
        )

        val works = LibraryWorks.build(listOf(record), File(root))

        assertEquals(2, works.size)
        val live = works[0]
        val clip = works[1]
        assertEquals(liveToken, live.token)
        assertEquals("如花似梦是我们短暂的相逢", live.title)
        assertEquals("nie11", live.author)
        assertEquals(1_794_851_452_000L, live.publishTimeMs)
        assertEquals(File(liveVideo).parent, live.dir)
        assertEquals(listOf("动图", "图片 1"), live.files.map { it.label })
        assertEquals("动图", live.typeLabel)
        // 卡片封面取作品里第一张图片
        assertTrue(live.coverPath.endsWith("_01_cover.webp"))

        assertEquals("人太多大部分是漫无目的的走#夏天#蓝调#傍晚", clip.title)
        assertEquals(listOf("视频", "封面"), clip.files.map { it.label })
        assertEquals("视频", clip.typeLabel)
        assertEquals(cover, clip.coverPath)
    }

    @Test
    fun build_mergesTheSameWorkAcrossRecordsAndKeepsNewestTitle() {
        val dir = "2026-11-17_如花似梦是我们短暂的相逢_$liveToken"
        val video = touch("$authorDir/$dir/如花似梦是我们短暂的相逢_${liveToken}_01.mp4")
        val still = touch("$authorDir/$dir/如花似梦是我们短暂的相逢_${liveToken}_01_cover.webp")
        val newer = batch(video, still).copy(
            batchId = 2, isProfile = false, title = "如花似梦是我们短暂的相逢",
            itemTitles = listOf("如花似梦是我们短暂的相逢", "如花似梦是我们短暂的相逢"),
            itemAuthors = listOf("nie11", "nie11")
        )
        val older = batch(video).copy(batchId = 1)

        val works = LibraryWorks.build(listOf(newer, older), File(root))

        assertEquals(1, works.size)
        assertEquals(listOf(video, still), works.single().files.map { it.path })
        assertEquals("如花似梦是我们短暂的相逢", works.single().title)
    }

    @Test
    fun build_restoresTitleAndAuthorFromLegacyFlatNames() {
        val legacy = touch(
            "$authorDir/20261117_如花似梦是我们短暂的相逢_5_${'m'}$liveToken" + "_20261124_175314.mp3"
        )
        val works = LibraryWorks.build(listOf(batch(legacy).copy(publishTimes = listOf(1_794_851_452_000L))), File(root))

        val work = works.single()
        assertEquals("如花似梦是我们短暂的相逢", work.title)
        assertEquals("nie11", work.author)
        assertEquals("背景音乐", work.files.single().label)
        assertEquals("音乐", work.typeLabel)
    }

    @Test
    fun build_legacyWorkSplitAcrossTwoMarkersIsOneWork() {
        // 旧版把图集逐条送云端重解析：同一个作品的文件带着两套标识（列表给的 / 云端给的）
        val other = "abc123def0"
        val music = touch("$authorDir/20261117_如花似梦是我们短暂的相逢_5_m${liveToken}_20261124_175314.mp3")
        val video = touch("$authorDir/20261117_如花似梦是我们短暂的相逢_6_m${other}_20261124_175315.mp4")
        val still = touch("$authorDir/20261117_如花似梦是我们短暂的相逢_10_m${other}_20261124_175317.jpeg")
        val t = 1_794_851_452_000L
        val record = batch(music, video, still).copy(publishTimes = listOf(t, t, t))

        val works = LibraryWorks.build(listOf(record), File(root))

        val work = works.single()
        assertEquals(listOf(music, video, still), work.files.map { it.path })
        assertEquals("如花似梦是我们短暂的相逢", work.title)
        assertEquals("视频", work.typeLabel)
        assertEquals("背景音乐", work.files.first().label)
    }

    @Test
    fun build_findsWorksWithoutAnyRecordByScanningTheRoot() {
        // 记录被清空、或文件是旧版本 / 别的设备留下的：只凭磁盘上的文件也要能列出作品
        val liveDir = "2026-11-17_如花似梦是我们短暂的相逢_$liveToken"
        val video = touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01.mp4", 300)
        val still = touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01_cover.webp", 70)
        File(video).setLastModified(1_794_851_452_000L)
        val legacy = touch("$authorDir/20260326_人太多_3_m${videoToken}_20261124_175313.mp4", 900)
        File(legacy).setLastModified(1_774_509_090_000L)
        // 不是本应用下载的文件（文件名里没有作品标识）不归进来
        touch("$root/随手存的照片.jpg")

        val works = LibraryWorks.build(emptyList(), root = File(root))

        assertEquals(2, works.size)
        val live = works.first { it.token == liveToken }
        assertEquals("如花似梦是我们短暂的相逢", live.title)
        assertEquals("nie11", live.author)
        assertEquals(Platform.DOUYIN, live.platform)
        assertEquals(1_794_851_452_000L, live.publishTimeMs)
        assertEquals(2, live.files.size)
        assertEquals("动图", live.typeLabel)
        val clip = works.first { it.token != liveToken }
        assertEquals("人太多", clip.title)
        assertEquals(900L, clip.files.first().sizeBytes)
        assertTrue(still.endsWith("_01_cover.webp"))
    }

    @Test
    fun build_diskScanMergesIntoRecordedWorksWithoutDuplicating() {
        val liveDir = "2026-11-17_如花似梦是我们短暂的相逢_$liveToken"
        val video = touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01.mp4")
        touch("$authorDir/$liveDir/如花似梦是我们短暂的相逢_${liveToken}_01_cover.webp")
        val record = batch(video).copy(itemTitles = listOf("如花似梦是我们短暂的相逢"), itemAuthors = listOf("nie11"))

        val works = LibraryWorks.build(listOf(record), root = File(root))

        assertEquals(1, works.size)
        assertEquals(2, works.single().files.size)
    }

    @Test
    fun build_newLayoutIsGroupedByTokenEvenWhenTitlesAreEqual() {
        // 文案相同的两个作品（新版排版）：标识不同就是两个作品
        val a = touch("$authorDir/2026-11-17_文案_${liveToken}/文案_${liveToken}.mp4")
        val b = touch("$authorDir/2026-11-17_文案_abc123def0/文案_abc123def0.mp4")
        assertEquals(2, LibraryWorks.build(listOf(batch(a, b).copy(publishTimes = listOf(5L, 5L))), File(root)).size)
    }

    @Test
    fun build_singleLinkDownloadHasNoAuthor() {
        val dir = "2026-03-26_人太多_$videoToken"
        val video = touch("$root/Douyin/$dir/人太多_$videoToken.mp4")
        val work = LibraryWorks.build(listOf(batch(video).copy(isProfile = false)), File(root)).single()
        assertEquals("", work.author)
        assertEquals("人太多", work.title)
        assertEquals(File(video).parent, work.dir)
    }

    @Test
    fun build_ignoresItemsWithoutPathOrToken() {
        val video = touch("$authorDir/2026-03-26_人太多_$videoToken/人太多_$videoToken.mp4")
        val record = batch(video, "", "/d/没有标识的文件.mp4")
        assertEquals(1, LibraryWorks.build(listOf(record), File(root)).size)
    }

    @Test
    fun typeLabels_imagePostAndStaticImageWithMusic() {
        val d = "2026-10-23_熟悉_$liveToken"
        fun f(name: String) = LibraryWorks.Item(
            path = "$authorDir/$d/$name", kind = "", label = "", durationMs = 0, sizeBytes = 0,
            partNo = WorkLayout.tailOf(name)?.partNo ?: 0,
            isCover = WorkLayout.tailOf(name)?.cover == true
        )
        val one = listOf(f("熟悉_${liveToken}_01.webp"), f("熟悉_${liveToken}_bgm.mp3"))
        assertEquals("图片", LibraryWorks.typeLabelOf(one))
        assertEquals(listOf("图片 1", "背景音乐"), one.map { LibraryWorks.labelOf(it, one) })
        val two = listOf(f("熟悉_${liveToken}_01.jpg"), f("熟悉_${liveToken}_02.jpg"))
        assertEquals("图集", LibraryWorks.typeLabelOf(two))
        assertEquals(listOf("图片 1", "图片 2"), two.map { LibraryWorks.labelOf(it, two) })
    }

    @Test
    fun titleOf_and_authorOf_handleBothLayouts() {
        assertEquals(
            "如花似梦是我们短暂的相逢",
            LibraryWorks.titleOf("$authorDir/2026-11-17_如花似梦是我们短暂的相逢_$liveToken/x_$liveToken.mp4", liveToken)
        )
        assertEquals(
            "人太多",
            LibraryWorks.titleOf("$authorDir/20260326_人太多_3_m${videoToken}_20261124_175313.mp4", videoToken)
        )
        assertEquals("nie11", LibraryWorks.authorOf(Platform.DOUYIN, "$root/Douyin/nie11/x.mp4", videoToken))
        assertEquals("", LibraryWorks.authorOf(Platform.DOUYIN, "$root/Douyin/2026-03-26_人太多_$videoToken/x.mp4", videoToken))
        assertEquals("", LibraryWorks.authorOf(Platform.DOUYIN, "$root/Douyin/x.mp4", videoToken))
        assertEquals("", LibraryWorks.authorOf(Platform.DOUYIN, "$root/x.mp4", videoToken))
    }
}
