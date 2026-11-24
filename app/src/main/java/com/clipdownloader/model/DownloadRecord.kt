package com.clipdownloader.model

import java.util.concurrent.atomic.AtomicBoolean

/** 下载批量状态（供「下载」页实时展示） */
class DownloadBatchState(
    val batchId: Long,
    val shareText: String,
    val platform: Platform,
    val startTime: Long = System.currentTimeMillis(),
    /** 博主主页批量下载标记：列表里归入「主页下载」栏目（纯文字行，无缩略图） */
    val isProfile: Boolean = false
) {
    enum class Phase { PARSING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    @Volatile var phase: Phase = Phase.PARSING
        private set

    /** 当前正在下载第几个（从 0 开始） */
    @Volatile var currentIndex: Int = 0
        private set

    @Volatile var totalItems: Int = 0
        private set

    /** 当前文件已下载字节 / 总字节（总字节未知为 -1） */
    @Volatile var currentBytes: Long = 0L
        private set

    @Volatile var currentTotalBytes: Long = -1L
        private set

    @Volatile var currentTitle: String = ""
        private set

    @Volatile var message: String = ""

    @Volatile var results: List<DownloadResult> = emptyList()
        private set

    /** 请求取消（在 IO 线程被检查） */
    val cancelRequested = AtomicBoolean(false)

    @Volatile var endTime: Long = 0L

    /** 列表缩略图（首个媒体的封面/图片地址） */
    @Volatile var coverUrl: String = ""

    /** 多图时的多个缩略图（不设上限，下载多少显示多少） */
    @Volatile var coverUrls: List<String> = emptyList()

    /** 展示标题（作者名优先，识别不到退回链接） */
    @Volatile var title: String = ""

    /** 当前条目媒体类型与时长（下载循环中更新，列表角标用） */
    @Volatile var currentKind: String = ""
    @Volatile var currentDuration: Long = 0L

    /** 第一个条目的类型/时长（写入历史记录用，避免多图任务被最后一条污染） */
    @Volatile var firstKind: String = ""
    @Volatile var firstDuration: Long = 0L

    /** 每个条目的类型/时长（下载循环追加，与缩略图一一对应） */
    val itemKinds = java.util.concurrent.CopyOnWriteArrayList<String>()
    val itemDurations = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val itemPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** 每个条目的文件大小（字节，下载循环追加，缩略图大小角标/总大小用） */
    val itemSizes = java.util.concurrent.CopyOnWriteArrayList<Long>()
    /** 每个条目是否「本地已存在跳过」（下载循环追加，与缩略图一一对应） */
    val itemSkipped = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    /** 首个条目的平台作品 ID（BV 号/aweme_id 等，记录归档用） */
    @Volatile var workId: String = ""

    /** 每个条目对应作品的发布时间（毫秒，0=未知；下载循环追加，与缩略图一一对应） */
    val itemPublishTimes = java.util.concurrent.CopyOnWriteArrayList<Long>()

    /** 每个条目的作品文案、作者（下载循环追加，与缩略图一一对应；作品库按作品归并时用） */
    val itemTitles = java.util.concurrent.CopyOnWriteArrayList<String>()
    val itemAuthors = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 本批各条目的作品标识（写在文件名与目录名末尾），保存记录时清理同作品旧记录条目用 */
    val workMarkers = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 本批各作品的落盘命名（作品标识 → 命名），同作品的条目共用；只在下载循环线程里读写 */
    val workNamings = HashMap<String, WorkNaming>()

    /** 博主主页下载时的子文件夹（按博主名归档） */
    @Volatile var subFolder: String? = null

    /** 博主主页下载：博主头像（列表条目缩略图用） */
    @Volatile var profileAvatar: String = ""


    fun markParsing() {
        phase = Phase.PARSING
        message = "正在解析链接…"
    }

    fun markDownloading(index: Int, total: Int, title: String) {
        phase = Phase.DOWNLOADING
        currentIndex = index
        totalItems = total
        currentTitle = title
        currentBytes = 0L
        currentTotalBytes = -1L
        message = "正在下载（${index + 1}/$total）"
    }

    fun updateFileProgress(downloaded: Long, total: Long) {
        currentBytes = downloaded
        currentTotalBytes = total
    }

    fun markCompleted(results: List<DownloadResult>) {
        phase = Phase.COMPLETED
        this.results = results
        endTime = System.currentTimeMillis()
        message = "已完成"
    }

    fun markFailed(reason: String) {
        phase = Phase.FAILED
        message = reason
        endTime = System.currentTimeMillis()
    }

    fun markCancelled() {
        phase = Phase.CANCELLED
        endTime = System.currentTimeMillis()
        message = "已取消"
    }

    val isActive: Boolean
        get() = phase == Phase.PARSING || phase == Phase.DOWNLOADING
}

/** 一个作品在本批的落盘命名：文案取该作品第一个非音乐条目，同作品所有文件共用（规则见 WorkLayout） */
data class WorkNaming(
    val desc: String,
    val publishTimeMs: Long,
    val folderName: String,
    /** 覆盖重下时本地已有该作品的文件：新文件存进它原来的作品目录（原地替换），为空按规则新建 */
    val dir: java.io.File? = null
)

/** 下载历史记录（持久化到 JSON 文件） */
data class DownloadRecord(
    val batchId: Long,
    val platform: Platform,
    val title: String,
    val fileName: String = "",
    val filePath: String = "",
    val success: Boolean,
    val error: String? = null,
    val isMotionPhoto: Boolean = false,
    val fileSize: Long = 0L,
    val itemCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val coverUrl: String = "",
    val coverUrls: List<String> = emptyList(),
    /** 媒体类型：视频/图片/音乐/动图 */
    val kind: String = "",
    /** 时长（毫秒），无则 0 */
    val durationMs: Long = 0L,
    /** 多媒体任务：每个条目的类型与时长（与缩略图一一对应） */
    val kinds: List<String> = emptyList(),
    val durations: List<Long> = emptyList(),
    /** 每个条目的文件大小（字节，与缩略图一一对应；未知为 0，缩略图角标用） */
    val sizes: List<Long> = emptyList(),
    /** 多媒体任务：每个成功条目的本地路径（与缩略图一一对应，点击缩略图打开对应文件） */
    val filePaths: List<String> = emptyList(),
    /** 每个条目是否「本地已存在跳过」（与缩略图一一对应，格子右上角「已存在」角标用；
     *  跳过条目路径非空——指向已有文件，光靠路径判不出跳过） */
    val itemSkipped: List<Boolean> = emptyList(),
    /** 每个条目的错误信息（与缩略图一一对应，null/空=成功或跳过；
     *  格子右上角「失败」角标用——光凭 success 布尔判不出哪个格子失败） */
    val itemErrors: List<String?> = emptyList(),
    /** 博主主页批量下载：记录归入「主页下载」栏目，缩略图用头像 */
    val isProfile: Boolean = false,
    /** 博主主页批量下载：博主头像（记录条目缩略图） */
    val avatarUrl: String = "",
    /** 原始分享文本（强制重新下载时重新解析用；旧记录无此字段） */
    val shareText: String = "",
    /** 本地已存在而跳过下载的条目数（记录卡片标明用） */
    val skippedCount: Int = 0,
    /** 本批真正新下载成功的条目数（不含跳过；统计行「成功 N」用，
     *  跳过条目路径也占 filePaths，光靠路径数会把跳过算进成功。
     *  可空：旧记录没这个字段（反序列化为 null）时 UI 回退路径数近似） */
    val okCount: Int? = null,
    /** 单作品的平台作品 ID（BV 号/aweme_id 等，链接里有的那个；旧记录为空） */
    val mediaId: String = "",
    /** 首个条目的作品发布时间（毫秒，0=未知；旧记录为 0） */
    val publishTimeMs: Long = 0L,
    /** 每个条目对应作品的发布时间（毫秒，0=未知；与缩略图一一对应） */
    val publishTimes: List<Long> = emptyList(),
    /** 每个条目的作品文案（与缩略图一一对应；旧记录没有，作品库从目录名 / 文件名还原） */
    val itemTitles: List<String> = emptyList(),
    /** 每个条目的作者（与缩略图一一对应；旧记录没有，作品库从路径还原） */
    val itemAuthors: List<String> = emptyList(),
) {
    /**
     * 去掉指向 [paths] 里文件的条目（文件被覆盖、删除后同步记录用）。
     * 没有要去的条目返回自身；文件条目全部去光返回 null（调用方应整条移除记录）。
     * 各条目列表（路径、封面、类型、时长、大小、发布时间、状态）按下标一一对应，一起裁剪；
     * 空路径（下载失败的条目）不算文件，也不删。
     */
    fun withoutPaths(paths: Set<String>): DownloadRecord? {
        // 空路径是下载失败的条目，不算文件，也不在裁剪范围内
        val drop = paths.filter { it.isNotBlank() }.toSet()
        val oldPaths = filePaths
        if (oldPaths.none { it in drop }) return this
        val keep = oldPaths.indices.filter { oldPaths[it] !in drop }
        val first = keep.firstOrNull { oldPaths[it].isNotBlank() } ?: return null
        fun <T> pick(list: List<T>): List<T> = keep.mapNotNull { list.getOrNull(it) }
        val newPaths = keep.map { oldPaths[it] }
        val skipped = pick(itemSkipped)
        return copy(
            filePaths = newPaths,
            filePath = oldPaths[first],
            fileName = oldPaths[first].substringAfterLast('/'),
            coverUrl = coverUrls.getOrNull(first).orEmpty(),
            coverUrls = pick(coverUrls),
            kind = kinds.getOrNull(first)?.takeIf { it.isNotBlank() } ?: kind,
            durationMs = durations.getOrNull(first) ?: durationMs,
            publishTimeMs = publishTimes.getOrNull(first) ?: publishTimeMs,
            publishTimes = pick(publishTimes),
            kinds = pick(kinds),
            durations = pick(durations),
            sizes = pick(sizes),
            itemSkipped = skipped,
            itemErrors = pick(itemErrors),
            // 整批大小 / 成功数 / 跳过数都按剩下的条目重算
            fileSize = newPaths.filter { it.isNotBlank() }.sumOf { runCatching { java.io.File(it).length() }.getOrDefault(0L) },
            itemCount = newPaths.size,
            skippedCount = skipped.count { it },
            okCount = okCount?.let { newPaths.indices.count { i -> newPaths[i].isNotBlank() && !skipped[i] } },
            // 还留着文件条目的记录就是有效记录
            success = true,
            error = pick(itemErrors).firstOrNull { !it.isNullOrBlank() }
        )
    }
}
