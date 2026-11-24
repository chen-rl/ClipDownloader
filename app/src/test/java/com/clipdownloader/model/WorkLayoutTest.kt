package com.clipdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘排版规则的单元测试：文案清洗、目录名、作品内序号与实况配对、标识识别、文件名归属。
 */
class WorkLayoutTest {

    private val token = "06dee402ce"
    private val base = "文案_$token"

    private fun video(
        id: String,
        url: String = "https://cdn/$id.mp4",
        cover: String? = "https://cdn/$id-cover.jpg"
    ) = ParsedMedia(
        platform = Platform.DOUYIN, videoUrl = url, title = "文案",
        author = "nie11", mediaId = id, coverUrl = cover
    )

    private fun image(work: String, url: String) = ParsedMedia(
        platform = Platform.DOUYIN, imageUrl = url, title = "文案", author = "nie11", mediaId = work
    )

    /** 实况的动态视频条目：封面就是它的静态原图地址 */
    private fun liveVideo(work: String, stillUrl: String) = ParsedMedia(
        platform = Platform.DOUYIN, videoUrl = "https://cdn/$work-live.mp4", coverUrl = stillUrl,
        title = "文案", author = "nie11", mediaId = work, duration = 2000L
    )

    // ==================== 文案 ====================

    @Test
    fun clean_replacesForbiddenCharsAndKeepsEmoji() {
        // 文件系统不允许的字符、控制字符都换成空格，空白合并，首尾的点和空白去掉
        assertEquals("a b c d e f g h i j", WorkLayout.clean("""a/b\c:d*e?f"g<h>i|j"""))
        assertEquals("a b", WorkLayout.clean("a\u0001b"))
        assertEquals("a b", WorkLayout.clean("a   \t\n b "))
        assertEquals("a.b", WorkLayout.clean("..a.b.."))
        // 合法的代理对（emoji）原样保留，落单的代理字符换成空格
        assertEquals("表情😀保留", WorkLayout.clean("表情😀保留"))
        assertEquals("a b", WorkLayout.clean("a \uD83D b"))
    }

    @Test
    fun shorten_neverSplitsSurrogatePair() {
        assertEquals("a".repeat(48), WorkLayout.shorten("a".repeat(48)))
        assertEquals("a".repeat(48), WorkLayout.shorten("a".repeat(48) + "b"))
        // 第 48、49 个单元是一个 emoji：截断要退一位，不能留下半个代理对
        assertEquals("a".repeat(47), WorkLayout.shorten("a".repeat(47) + "😀" + "b".repeat(10)))
    }

    @Test
    fun descOf_fallsBackToAuthorThenPlatform() {
        assertEquals("如花似梦", WorkLayout.descOf(video("1").copy(title = "如花似梦")))
        assertEquals("nie11", WorkLayout.descOf(video("1").copy(title = "  ")))
        assertEquals("抖音", WorkLayout.descOf(video("1").copy(title = "", author = "")))
        assertEquals(
            WorkLayout.MAX_DESC_LENGTH,
            WorkLayout.descOf(video("1").copy(title = "长".repeat(100))).length
        )
    }

    // ==================== 目录名 / 文件名 ====================

    @Test
    fun folderName_startsWithPublishDateOnlyWhenKnown() {
        assertEquals("文案_$token", WorkLayout.folderName(0, "文案", token))
        // 2026-11-17 01:50:52（东八区）；任何时区都落在某个合法日期上
        val name = WorkLayout.folderName(1_794_851_452_000L, "文案", token)
        assertTrue(name, name.matches(Regex("\\d{4}-\\d{2}-\\d{2}_文案_$token")))
    }

    @Test
    fun numbered_addsTwoDigitPartNumber() {
        assertEquals(base, WorkLayout.numbered(base, 0))
        assertEquals("${base}_01", WorkLayout.numbered(base, 1))
        assertEquals("${base}_12", WorkLayout.numbered(base, 12))
    }

    // ==================== 序号与实况配对 ====================

    @Test
    fun assign_singleVideoIsNotNumbered() {
        val out = WorkLayout.assign(listOf(video("1")))
        assertEquals(0, out[0].partNo)
        assertFalse(out[0].livePair)
    }

    @Test
    fun assign_numbersImagesInOrderAndPairsEachLiveVideoWithItsStill() {
        // a 静态图；b 实况（视频条目排在图片前面）；c 静态图
        val out = WorkLayout.assign(listOf(
            image("1", "https://cdn/a.jpg"),
            video("1", url = "https://cdn/b-live.mp4", cover = "https://cdn/b.jpg"),
            image("1", "https://cdn/b.jpg"),
            image("1", "https://cdn/c.jpg")
        ))
        assertEquals(listOf(1, 2, 2, 3), out.map { it.partNo })
        assertEquals(listOf(false, true, true, false), out.map { it.livePair })
    }

    @Test
    fun assign_liveVideoListedAfterItsStill() {
        val out = WorkLayout.assign(listOf(
            image("1", "https://cdn/a.jpg"),
            image("1", "https://cdn/b.jpg"),
            liveVideo("1", "https://cdn/b.jpg")
        ))
        assertEquals(listOf(1, 2, 2), out.map { it.partNo })
        assertEquals(listOf(false, true, true), out.map { it.livePair })
    }

    @Test
    fun assign_unpairedVideoInImageWork_getsNextNumber() {
        val out = WorkLayout.assign(listOf(image("1", "https://cdn/a.jpg"), video("1")))
        assertEquals(listOf(1, 2), out.map { it.partNo })
        assertEquals(listOf(false, false), out.map { it.livePair })
    }

    @Test
    fun assign_multiVideoWork_numbersVideos() {
        val out = WorkLayout.assign(listOf(video("1"), video("1", url = "https://cdn/2.mp4")))
        assertEquals(listOf(1, 2), out.map { it.partNo })
        assertFalse(out[0].livePair)
    }

    @Test
    fun assign_motionPhotoItemTakesOneNumberAndIsLive() {
        val live = ParsedMedia(
            platform = Platform.XIAOHONGSHU, videoUrl = "https://cdn/l.mp4", imageUrl = "https://cdn/l.jpg",
            title = "t", mediaId = "9", isMotionPhoto = true
        )
        val out = WorkLayout.assign(listOf(image("9", "https://cdn/a.jpg"), live))
        assertEquals(listOf(1, 2), out.map { it.partNo })
        assertEquals(listOf(false, true), out.map { it.livePair })
    }

    @Test
    fun assign_musicIsNeverNumbered() {
        val music = ParsedMedia(
            platform = Platform.DOUYIN, videoUrl = "https://cdn/m.mp3", title = "t", mediaId = "1", isMusic = true
        )
        val out = WorkLayout.assign(listOf(image("1", "https://cdn/a.jpg"), music))
        assertEquals(listOf(1, 0), out.map { it.partNo })
        assertFalse(out[1].livePair)
    }

    @Test
    fun assign_numbersEachWorkIndependently() {
        val out = WorkLayout.assign(listOf(
            video("1"), image("2", "https://cdn/a.jpg"), image("2", "https://cdn/b.jpg")
        ))
        assertEquals(listOf(0, 1, 2), out.map { it.partNo })
    }

    // ==================== 标识识别 ====================

    @Test
    fun tokenOf_readsNewLayoutNames() {
        assertEquals(token, WorkLayout.tokenOf("$base.mp4"))
        assertEquals(token, WorkLayout.tokenOf("${base}_01.jpg"))
        assertEquals(token, WorkLayout.tokenOf("${base}_02.mp4"))
        assertEquals(token, WorkLayout.tokenOf("${base}_02_cover.jpg"))
        assertEquals(token, WorkLayout.tokenOf("${base}_bgm.mp3"))
        // 另存产生的 (1) 后缀
        assertEquals(token, WorkLayout.tokenOf("$base(1).mp4"))
        // 作品目录名
        assertEquals(token, WorkLayout.tokenOf("2026-11-17_如花似梦_$token"))
        assertNull(WorkLayout.tokenOf("没有标识的文件.mp4"))
    }

    @Test
    fun tokenOf_readsLegacyFlatNames() {
        assertEquals(token, WorkLayout.tokenOf("20261117_如花似梦_5_m${token}_20261124_175314.mp3"))
        assertEquals(token, WorkLayout.tokenOf("20261113_你留下的痕迹_13_m${token}_20261124_175319_live.jpeg"))
    }

    @Test
    fun tokenOf_ignoresHexLookingFragmentsInsideTitle() {
        // 文案里凑巧有「下划线 + 10 位数字 + 下划线」：后面跟的不是固定后缀，不算标识，
        // 真正的标识（在文件名末尾）照常能找到
        assertEquals(token, WorkLayout.tokenOf("改版_2026010100_$base.mp4"))
        assertNull(WorkLayout.tokenOf("改版_2026010100_x.mp4"))
        assertNull(WorkLayout.tokenOf("改版_2026010100_x_01.jpg"))
    }

    @Test
    fun descFromFolder_stripsDateAndToken() {
        assertEquals("如花似梦", WorkLayout.descFromFolder("2026-11-17_如花似梦_$token"))
        assertEquals("如花似梦", WorkLayout.descFromFolder("如花似梦_$token"))
    }

    // ==================== 文件名归属 ====================

    @Test
    fun withoutDupSuffix_restoresTheCleanNameKeepingTheExtension() {
        // 同名另存的 (1) 去掉以后，扩展名必须还在
        assertEquals("$base.mp4", WorkLayout.withoutDupSuffix("$base(1).mp4"))
        assertEquals("${base}_01_cover.webp", WorkLayout.withoutDupSuffix("${base}_01_cover(12).webp"))
        // 不是另存后缀的情况：没有扩展名 / 括号里不是数字 / 没有括号
        assertNull(WorkLayout.withoutDupSuffix("$base(1)"))
        assertNull(WorkLayout.withoutDupSuffix("$base(x).mp4"))
        assertNull(WorkLayout.withoutDupSuffix("$base.mp4"))
    }

    @Test
    fun tailOf_parsesPartNumberAndCover() {
        val cover = WorkLayout.tailOf("${base}_03_cover.jpg")!!
        assertEquals(base, cover.base)
        assertEquals(3, cover.partNo)
        assertTrue(cover.cover)

        val plain = WorkLayout.tailOf("$base.mp4")!!
        assertEquals(base, plain.base)
        assertEquals(0, plain.partNo)
        assertFalse(plain.cover)

        val dup = WorkLayout.tailOf("${base}_01(2).jpg")!!
        assertEquals(1, dup.partNo)
    }

    @Test
    fun isItemFile_matchesTypePartNumberAndCover() {
        val still = image("1", "https://cdn/b.jpg").copy(partNo = 2, livePair = true)
        val live = liveVideo("1", "https://cdn/b.jpg").copy(partNo = 2, livePair = true)
        val photo = image("1", "https://cdn/a.jpg").copy(partNo = 1)
        val clip = video("1").copy(partNo = 0)
        val music = ParsedMedia(
            platform = Platform.DOUYIN, videoUrl = "https://cdn/m.mp3", title = "t", mediaId = "1", isMusic = true
        )

        // 实况的静态原图：同序号 + _cover 的图片
        assertTrue(WorkLayout.isItemFile("${base}_02_cover.jpg", still))
        assertFalse("静态原图不能拿动态视频充数", WorkLayout.isItemFile("${base}_02.mp4", still))
        assertFalse("别的序号的封面不是它的", WorkLayout.isItemFile("${base}_01_cover.jpg", still))
        // 实况的动态视频：同序号的视频
        assertTrue(WorkLayout.isItemFile("${base}_02.mp4", live))
        assertFalse(WorkLayout.isItemFile("${base}_02_cover.jpg", live))
        // 图集的图片：同序号、不带 _cover
        assertTrue(WorkLayout.isItemFile("${base}_01.jpg", photo))
        assertFalse("第 1 张不能错拿第 2 张", WorkLayout.isItemFile("${base}_02.jpg", photo))
        assertFalse("普通图片不能拿封面充数", WorkLayout.isItemFile("${base}_01_cover.jpg", photo))
        // 不编号的单个视频：文件名也不带序号
        assertTrue(WorkLayout.isItemFile("$base.mp4", clip))
        assertTrue(WorkLayout.isItemFile("$base(1).mp4", clip))
        assertFalse(WorkLayout.isItemFile("${base}_01.mp4", clip))
        assertFalse("视频不能拿图片充数", WorkLayout.isItemFile("${base}_01_cover.jpg", clip))
        // 背景音乐
        assertTrue(WorkLayout.isItemFile("${base}_bgm.mp3", music))
        assertFalse(WorkLayout.isItemFile("${base}_01.mp3", music))
    }

    @Test
    fun isItemFile_imageSlotAcceptsTheOtherImageNameOnlyWhenAllowed() {
        val photo = image("1", "https://cdn/a.jpg").copy(partNo = 1)
        val still = image("1", "https://cdn/b.jpg").copy(partNo = 2, livePair = true)
        val clip = video("1").copy(partNo = 0)
        // 精确写法
        assertTrue(WorkLayout.isItemFile("${base}_01.jpg", photo))
        assertTrue(WorkLayout.isItemFile("${base}_02_cover.webp", still))
        // 同一个位置的另一种写法：默认不认，放宽后才认
        assertFalse(WorkLayout.isItemFile("${base}_01_cover.webp", photo))
        assertTrue(WorkLayout.isItemFile("${base}_01_cover.webp", photo, otherImageName = true))
        assertFalse(WorkLayout.isItemFile("${base}_02.jpg", still))
        assertTrue(WorkLayout.isItemFile("${base}_02.jpg", still, otherImageName = true))
        // 放宽只对图片条目：视频条目永远不认图片，序号不同也不认
        assertFalse(WorkLayout.isItemFile("${base}_01_cover.webp", clip, otherImageName = true))
        assertFalse(WorkLayout.isItemFile("${base}_02_cover.webp", photo, otherImageName = true))
    }

    // ==================== 覆盖替换范围 ====================

    @Test
    fun isReplacedBy_onlyTheFileThatWasActuallyRedownloaded() {
        // 只重下了视频：旧视频替换，同一作品的封面、别的序号的文件都不动
        assertTrue(WorkLayout.isReplacedBy(listOf("$base.mp4"), "$base.mp4"))
        assertTrue(WorkLayout.isReplacedBy(listOf("$base(1).mp4"), "$base.mp4"))
        assertFalse(WorkLayout.isReplacedBy(listOf("$base.mp4"), "${base}_01_cover.webp"))
        assertFalse(WorkLayout.isReplacedBy(listOf("$base.mp4"), "${base}_02.mp4"))
        // 只重下了实况的视频：旧封面（静态原图）不动
        assertTrue(WorkLayout.isReplacedBy(listOf("${base}_01.mp4"), "${base}_01.mp4"))
        assertFalse(WorkLayout.isReplacedBy(listOf("${base}_01.mp4"), "${base}_01_cover.webp"))
        // 视频和封面一起重下：旧封面即使扩展名不同也替换
        assertTrue(
            WorkLayout.isReplacedBy(listOf("$base.mp4", "${base}_01_cover(1).jpg"), "${base}_01_cover.webp")
        )
        // 同序号但类型不同（图片与实况视频共用序号）不算同一个文件
        assertFalse(WorkLayout.isReplacedBy(listOf("${base}_01.webp"), "${base}_01.mp4"))
        assertFalse(WorkLayout.isReplacedBy(listOf("${base}_01.mp4"), "${base}_01.webp"))
    }

    @Test
    fun isReplacedBy_imageSlotInterchangesPlainAndCoverNames() {
        // `_01.jpg` 与 `_01_cover.jpg` 是同一个位置：新下的是一种写法，旧的另一种也替换
        assertTrue(WorkLayout.isReplacedBy(listOf("${base}_01(1).webp"), "${base}_01_cover.webp"))
        assertTrue(WorkLayout.isReplacedBy(listOf("${base}_01_cover.webp"), "${base}_01.jpg"))
        // 但只重下图片时，视频不动
        assertFalse(WorkLayout.isReplacedBy(listOf("${base}_01.webp"), "${base}_01.mp4"))
        // 背景音乐：换一种音频格式也是替换它
        assertTrue(WorkLayout.isReplacedBy(listOf("${base}_bgm.mp3"), "${base}_bgm.m4a"))
        assertFalse(WorkLayout.isReplacedBy(listOf("${base}_bgm.mp3"), "$base.mp4"))
    }

    @Test
    fun isReplacedBy_legacyFlatNamesAreReplacedByMediaType() {
        val legacyVideo = "20260326_文案_3_m${token}_20261124_175313.mp4"
        val legacyImage = "20261113_文案_13_m${token}_20261124_175319_live.jpeg"
        val legacyAudio = "20261117_文案_5_m${token}_20261124_175314.mp3"
        // 只重下了图片：旧版文件里只有图片被替换，视频和音频留着
        val new = listOf("${base}_01_cover.webp")
        assertTrue(WorkLayout.isReplacedBy(new, legacyImage))
        assertFalse(WorkLayout.isReplacedBy(new, legacyVideo))
        assertFalse(WorkLayout.isReplacedBy(new, legacyAudio))
        // 重下了图片和背景音乐
        assertTrue(WorkLayout.isReplacedBy(new + "${base}_bgm.mp3", legacyAudio))
        // 新文件里没有视频，旧视频不动
        assertFalse(WorkLayout.isReplacedBy(new + "${base}_bgm.mp3", legacyVideo))
        assertTrue(WorkLayout.isReplacedBy(listOf("$base.mp4"), legacyVideo))
        // 认不出类型的文件不动
        assertFalse(WorkLayout.isReplacedBy(listOf("$base.mp4"), "${token}_m${token}_x.txt"))
    }
}
