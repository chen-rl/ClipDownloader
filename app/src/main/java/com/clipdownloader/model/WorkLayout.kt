package com.clipdownloader.model

/**
 * 落盘排版：一个作品一个文件夹，文件名只有「文案 + 作品标识」。
 * 纯 Kotlin、不依赖 Android，便于单元测试。
 *
 * 命名规则借鉴 DYDownloader 的排版（只借鉴规则，代码独立实现）：
 * ```
 * <保存路径>/[平台/][博主/]<yyyy-MM-dd>_<文案>_<标识>/
 *     <文案>_<标识>.mp4             单个视频
 *     <文案>_<标识>_01_cover.jpg    它的封面
 *     <文案>_<标识>_01.jpg          图集第 1 张
 *     <文案>_<标识>_02.mp4          实况（动图）第 2 张的动态视频
 *     <文案>_<标识>_02_cover.jpg    实况第 2 张的静态原图
 *     <文案>_<标识>_bgm.mp3         图集背景音乐
 * ```
 * - 发布日期放在目录名最前面（按名称排序即按发布时间排序），未知时不带日期；
 *   文件名不再重复日期。
 * - 标识沿用作品去重用的 10 位哈希，同文案的不同作品靠它区分；
 *   旧版平铺文件名（`…_m<标识>_…`）里的标识同样能被认出。
 * - 图片 / 实况按出现顺序编号；实况的动态视频与静态原图共用一个序号。
 */
object WorkLayout {

    /** 目录名、文件名里「文案」部分的长度上限（UTF-16 单元；不会把 emoji 等代理对截成两半） */
    const val MAX_DESC_LENGTH = 48

    /** 封面 / 实况静态原图的后缀（前面带序号） */
    const val COVER_SUFFIX = "_cover"

    /** 背景音乐的后缀 */
    const val BGM_SUFFIX = "_bgm"

    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
    val VIDEO_EXTS = setOf("mp4", "mov", "m4v", "webm", "mkv")
    val AUDIO_EXTS = setOf("m4a", "mp3", "aac", "wav", "flac", "opus")

    /** 文件系统不允许的字符、控制字符、落单的代理字符：一律换成空格 */
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}\\p{Cs}]")

    private val BLANKS = Regex("\\s+")

    /**
     * 新版名称里的作品标识：`…_<标识>.mp4`、`…_<标识>_01_cover.jpg`、`…_<标识>_bgm.mp3`，
     * 另存产生的 `(1)` 之前，或目录名末尾。标识后面只能跟这些固定后缀——
     * 文案里凑巧出现的「下划线 + 10 位十六进制字符」后面跟的是别的内容，不会被误认。
     */
    private val TOKEN = Regex("_[0-9a-f]{10}(?=$|[.(]|_bgm[.(]|_\\d{2}(?:_cover)?[.(])")

    /** 旧版平铺文件名里的作品标识：`…_m<标识>_20261124_175315.mp4` */
    private val LEGACY_TOKEN = Regex("_m([0-9a-f]{10})_")

    /** 文件名主体的结尾：可选序号 + 可选 `_cover` */
    private val TAIL = Regex("^(.*?)(?:_(\\d{2}))?(_cover)?$")

    /** 另存产生的 `(1)` `(2)` 后缀（在扩展名之前）。捕获组：主体、序号、扩展名 */
    private val DUP_NAME = Regex("^(.*)\\((\\d+)\\)(\\.[^.]+)$")

    /** 目录名开头的发布日期 */
    private val DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}_")

    /** 目录名末尾的 `_标识` */
    private val FOLDER_TOKEN = Regex("_[0-9a-f]{10}$")

    /** 文案清洗：非法字符与控制字符换成空格，合并空白，去掉首尾的点 */
    fun clean(raw: String): String =
        raw.replace(FORBIDDEN, " ").replace(BLANKS, " ").trim().trim('.')

    /** 截到 [MAX_DESC_LENGTH] 以内，不把代理对（emoji 等）截成两半 */
    fun shorten(text: String): String {
        if (text.length <= MAX_DESC_LENGTH) return text
        var end = MAX_DESC_LENGTH
        if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        return text.substring(0, end).trim()
    }

    /** 作品文案：标题 → 作者 → 平台名，清洗截断后一定非空 */
    fun descOf(media: ParsedMedia): String =
        shorten(clean(media.title)).takeIf { it.isNotBlank() }
            ?: shorten(clean(media.author)).takeIf { it.isNotBlank() }
            ?: clean(media.platform.displayName).ifBlank { "作品" }

    /** 作品目录名：`<yyyy-MM-dd>_<文案>_<标识>`，发布日期未知时省略日期 */
    fun folderName(publishTimeMs: Long, desc: String, token: String): String {
        val date = PublishTime.dateText(publishTimeMs)
        return (if (date.isBlank()) "" else "${date}_") + desc + "_" + token
    }

    /** 文件名主体：`<文案>_<标识>` */
    fun fileBase(desc: String, token: String): String = desc + "_" + token

    /** 带序号的主体；序号为 0（不编号）时原样返回 */
    fun numbered(base: String, partNo: Int): String =
        if (partNo > 0) base + "_" + twoDigits(partNo) else base

    fun twoDigits(n: Int): String = if (n in 0..9) "0$n" else n.toString()

    /**
     * 给同一批解析结果里的条目标注作品内序号（[ParsedMedia.partNo]）与实况配对
     * （[ParsedMedia.livePair]）。同一作品（[ParsedMedia.mediaId] 相同）的条目按出现顺序处理：
     * - 图片各占一个序号；
     * - 视频条目的封面地址与同作品某张图片相同，说明是该图的实况动态视频，与它共用序号；
     * - 其余视频：作品里有图片时接着图片的序号往下编（避免与图片、封面重号）；
     *   没有图片时，单个视频不编号，多个视频依次编号；
     * - 实况图条目（同时带原图和动态视频）占一个序号；音乐不编号。
     *
     * 必须传入作品的完整条目列表：只传勾选中的那部分会把序号排错。
     */
    fun assign(list: List<ParsedMedia>): List<ParsedMedia> {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, m ->
            val key = m.mediaId.ifBlank { m.rawShareText.ifBlank { m.title } }
            groups.getOrPut(key) { mutableListOf() }.add(i)
        }
        val out = ArrayList<ParsedMedia>(list)

        fun hasVideo(m: ParsedMedia) = !m.videoUrl.isNullOrBlank()
        fun isLive(m: ParsedMedia) = m.isMotionPhoto && hasVideo(m) && !m.imageUrl.isNullOrBlank()
        fun isImage(m: ParsedMedia) = !isLive(m) && !m.imageUrl.isNullOrBlank()
        fun isVideo(m: ParsedMedia) = !isLive(m) && !m.isMusic && hasVideo(m)

        for (indices in groups.values) {
            val slot = IntArray(indices.size)
            val imageByUrl = HashMap<String, Int>()
            // 图片 / 实况条目按出现顺序占号
            var last = 0
            indices.forEachIndexed { k, i ->
                val m = list[i]
                if (isImage(m)) {
                    slot[k] = ++last
                    imageByUrl.putIfAbsent(m.imageUrl.orEmpty(), k)
                } else if (isLive(m)) {
                    slot[k] = ++last
                }
            }
            // 视频条目：封面与某张图片相同 → 实况配对；否则视作品情况编号
            val videoCount = indices.count { isVideo(list[it]) }
            val pairedImages = HashSet<Int>()
            val videoNo = HashMap<Int, Int>()
            val liveVideos = HashSet<Int>()
            indices.forEachIndexed { k, i ->
                val m = list[i]
                if (!isVideo(m)) return@forEachIndexed
                val imageKey = m.coverUrl?.takeIf { it.isNotBlank() }?.let { imageByUrl[it] }
                if (imageKey != null && pairedImages.add(imageKey)) {
                    videoNo[k] = slot[imageKey]
                    liveVideos.add(k)
                } else {
                    last = if (last > 0 || videoCount > 1) last + 1 else last
                    videoNo[k] = if (last > 0) last else 0
                }
            }
            indices.forEachIndexed { k, i ->
                val m = list[i]
                val live = isLive(m) || k in liveVideos || k in pairedImages
                val no = if (k in videoNo) videoNo.getValue(k) else slot[k]
                if (no <= 0 && !live) return@forEachIndexed
                out[i] = m.copy(partNo = no, livePair = live)
            }
        }
        return out
    }

    /** 文件名 / 目录名里的作品标识（新旧版文件名都认），认不出返回 null */
    fun tokenOf(name: String): String? =
        TOKEN.findAll(name).lastOrNull()?.value?.substring(1)
            ?: LEGACY_TOKEN.findAll(name).lastOrNull()?.groupValues?.get(1)

    /** 从作品目录名还原文案（去掉日期前缀与标识） */
    fun descFromFolder(folder: String): String =
        folder.replace(DATE_PREFIX, "").replace(FOLDER_TOKEN, "")

    /** 文件名结尾解析结果：主体、序号（没有为 0）、是否 `_cover` */
    data class Tail(val base: String, val partNo: Int, val cover: Boolean)

    /**
     * 去掉另存后缀后的文件名：`文案(1).mp4` → `文案.mp4`、`文案_01_cover(12).webp` → `文案_01_cover.webp`。
     * 没有这种后缀（没有扩展名、括号里不是数字）返回 null。
     */
    fun withoutDupSuffix(fileName: String): String? =
        DUP_NAME.find(fileName)?.let { it.groupValues[1] + it.groupValues[3] }

    /** 解析文件名的结尾（忽略扩展名与另存后缀 `(1)`） */
    fun tailOf(fileName: String): Tail? {
        val stem = (withoutDupSuffix(fileName) ?: fileName).substringBeforeLast('.', fileName)
        val m = TAIL.find(stem) ?: return null
        return Tail(
            base = m.groupValues[1],
            partNo = m.groupValues[2].toIntOrNull() ?: 0,
            cover = m.groupValues[3].isNotEmpty()
        )
    }

    /**
     * 已有文件是否就是该条目的落盘文件（跳过回填、防重复下载用）：
     * 类型对得上，新版文件名的序号与 `_cover` 也对得上。
     * 旧版平铺文件名没有序号，只能按类型对（单视频 / 实况视频可以，图集各张由调用方按旧序号配对）。
     *
     * @param otherImageName 图片条目是否也认「另一种写法」：实况静态原图存的是 `_NN_cover.*`，
     *        普通图片存的是 `_NN.*`，同一个作品从不同入口解析时标法可能不同（单链接入口拿不到
     *        实况视频时，静态图就是普通图片）。两种名字是同一个位置，先按精确写法找，找不到再放宽
     */
    fun isItemFile(fileName: String, media: ParsedMedia, otherImageName: Boolean = false): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val tail = tailOf(fileName) ?: return false
        val sameNo = tail.partNo == media.partNo
        return when {
            media.isMusic -> ext in AUDIO_EXTS && tail.base.endsWith(BGM_SUFFIX) && tail.partNo == 0 && !tail.cover
            media.isMotionPhoto && !media.videoUrl.isNullOrBlank() ->
                ext in VIDEO_EXTS && sameNo && !tail.cover
            // 实况的静态原图：与它的动态视频同序号，带 _cover
            media.livePair && !media.imageUrl.isNullOrBlank() ->
                ext in IMAGE_EXTS && sameNo && (tail.cover || otherImageName)
            // 实况的动态视频
            media.livePair -> ext in VIDEO_EXTS && sameNo && !tail.cover
            !media.imageUrl.isNullOrBlank() -> ext in IMAGE_EXTS && sameNo && (!tail.cover || otherImageName)
            !media.videoUrl.isNullOrBlank() -> ext in VIDEO_EXTS && sameNo && !tail.cover
            else -> false
        }
    }

    /** 文件按扩展名归的类：video / image / audio / other */
    fun mediaClassOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO_EXTS -> "video"
            ext in IMAGE_EXTS -> "image"
            ext in AUDIO_EXTS -> "audio"
            else -> "other"
        }
    }

    /**
     * 覆盖模式下，一个作品本次落盘的新文件 [newNames] 要替换掉哪些旧文件——返回 true 表示
     * 旧文件 [oldName] 应被替换。只动「这次真正重下了的文件」，作品里没被重下的其它文件不碰：
     * - 新版文件名：主体、序号、类型（视频 / 图片 / 音频）都相同才替换。图片位置的两种写法
     *   `_01.jpg` 与 `_01_cover.jpg` 算同一个位置（实况静态原图 / 普通图片），新下的是一种，
     *   旧的另一种也一并替换；扩展名不同不影响（视频重下后封面从 jpeg 变 webp 也算替换）；
     * - 旧版平铺文件名（`…_m<标识>_…`，没有序号结构）：按类型替换——新文件里有视频就替换
     *   旧的视频，有图片替换旧的图片，有音频替换旧的音频。
     */
    fun isReplacedBy(newNames: List<String>, oldName: String): Boolean {
        val oldTail = tailOf(oldName) ?: return false
        val oldClass = mediaClassOf(oldName)
        if (LEGACY_TOKEN.containsMatchIn(oldName)) {
            return oldClass != "other" && newNames.any { mediaClassOf(it) == oldClass }
        }
        return newNames.any { new ->
            val newTail = tailOf(new) ?: return@any false
            newTail.base == oldTail.base && newTail.partNo == oldTail.partNo &&
                mediaClassOf(new) == oldClass
        }
    }
}
