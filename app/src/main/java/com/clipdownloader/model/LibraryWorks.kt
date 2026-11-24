package com.clipdownloader.model

import java.io.File

/**
 * 作品库：把下载记录按「作品」归并，供作品库页（封面卡片墙）与作品详情页使用。
 * 纯 Kotlin、不依赖 Android，便于单元测试。
 *
 * 一个作品 = 文件名里带同一个作品标识的全部文件（见 [WorkLayout]）。
 *
 * 作品库以磁盘上实际有的文件为准，下载记录只用来补充信息（类型、时长、文案、作者）：
 * - 记录里登记的条目；
 * - 保存目录里有、但记录没登记的文件：视频封面、实况静态原图不占记录条目位；记录被清空、
 *   旧版本下载的、从别的设备拷过来的文件也都算。所以「清空记录」不会让作品库变空，
 *   文件删了作品才会消失。
 */
object LibraryWorks {

    /** 作品库里的一个文件 */
    data class Item(
        val path: String,
        /** 类型：视频 / 图片 / 音乐（记录里存的，旧记录没有则按扩展名推断） */
        val kind: String,
        /** 详情页里的说明文字：视频 / 视频 2 / 动图 / 图片 1 / 封面 / 背景音乐 */
        val label: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val partNo: Int,
        val isCover: Boolean,
    ) {
        val name: String get() = File(path).name
        val isVideo: Boolean get() = WorkLayout.mediaClassOf(name) == "video"
        val isImage: Boolean get() = WorkLayout.mediaClassOf(name) == "image"
    }

    /** 作品库里的一个作品 */
    data class Work(
        /** 作品库里的定位键（详情页用它找到这个作品）：新版排版的文件就是作品标识；
         *  旧版平铺文件是「所在目录 + 文案」合成的键 */
        val token: String,
        val platform: Platform,
        /** 作品文案 */
        val title: String,
        val author: String,
        val publishTimeMs: Long,
        /** 文件所在目录（作品目录）；文件散在多处时取第一个文件的 */
        val dir: String,
        val files: List<Item>,
        /** 卡片类型角标：视频 / 图集 / 图片 / 动图 / 音乐 */
        val typeLabel: String,
        /** 卡片封面：作品里第一张图片文件，其次是第一个视频，都没有为空 */
        val coverPath: String,
        /** 记录里存的远程封面（本地封面读不出来时兜底） */
        val coverUrl: String
    )

    /**
     * 归并中的一个作品。[key] 是作品库里的定位键：新版排版的文件就是作品标识；
     * 旧版平铺文件是「所在目录 + 文案」合成的键（见 [build]）。
     */
    private class Draft(val key: String, val legacy: Boolean) {
        var platform: Platform = Platform.OTHER
        var title = ""
        var author = ""
        var publishTimeMs = 0L
        var coverUrl = ""
        var dir = ""
        val paths = LinkedHashMap<String, Pair<String, Long>>()   // 路径 → (记录里的类型, 时长)
        val sizes = HashMap<String, Long>()
    }

    /**
     * 把下载记录归并成作品列表。记录按时间倒序传入时，作品顺序 = 首次出现的顺序。
     *
     * @param root 保存目录。除了记录里的条目，再扫一遍磁盘（根 / 平台 / 博主 / 作品目录 / 文件），
     * 把记录没登记的文件也归进来：视频封面、实况静态原图，以及记录已丢失的作品
     */
    fun build(records: List<DownloadRecord>, root: File): List<Work> {
        val drafts = LinkedHashMap<String, Draft>()
        records.forEach { record ->
            val paths = record.filePaths
            val titles = record.itemTitles
            val authors = record.itemAuthors
            paths.forEachIndexed { i, path ->
                if (path.isBlank()) return@forEachIndexed
                val name = File(path).name
                val token = WorkLayout.tokenOf(name) ?: return@forEachIndexed
                // 旧版把图集逐条送云端重解析，同一个作品的文件带着两套不同的作品标识
                // （主页列表给的与云端给的）。旧版平铺文件改按「所在目录 + 文案」归并，
                // 否则一个作品会在作品库里拆成两三张卡片；新版排版的文件仍按标识归并
                val legacy = WorkLayout.isLegacyName(name)
                val key = if (legacy) legacyKey(path, token) else token
                val d = drafts.getOrPut(key) { Draft(key, legacy) }
                d.paths.putIfAbsent(path, record.kinds.getOrNull(i).orEmpty() to (record.durations.getOrNull(i) ?: 0L))
                record.sizes.getOrNull(i)?.takeIf { it > 0 }?.let { d.sizes[path] = it }
                d.platform = record.platform
                // 文案 / 作者：记录里逐条目存的优先；旧记录没有，从目录名、文件名、路径里还原
                if (d.title.isBlank()) {
                    d.title = titles.getOrNull(i).orEmpty().ifBlank { titleOf(path, token) }
                }
                if (d.author.isBlank()) {
                    d.author = authors.getOrNull(i).orEmpty().ifBlank {
                        if (record.isProfile) record.title else authorOf(record.platform, path, token)
                    }
                }
                d.publishTimeMs = maxOf(d.publishTimeMs, record.publishTimes.getOrNull(i) ?: 0L)
                if (d.coverUrl.isBlank()) d.coverUrl = record.coverUrls.getOrNull(i).orEmpty()
                if (d.dir.isBlank()) d.dir = File(path).parent.orEmpty()
            }
        }
        scanRoot(root, drafts)
        return drafts.values.map { d ->
            val items = d.paths.map { (path, meta) ->
                val tail = WorkLayout.tailOf(File(path).name)
                val name = File(path).name
                Item(
                    path = path,
                    kind = meta.first.ifBlank {
                        when (WorkLayout.mediaClassOf(name)) {
                            "video" -> "视频"; "audio" -> "音乐"; else -> "图片"
                        }
                    },
                    label = "",
                    durationMs = meta.second,
                    sizeBytes = d.sizes[path] ?: 0L,
                    partNo = tail?.partNo ?: 0,
                    isCover = tail?.cover == true
                )
            }.let { files -> files.map { it.copy(label = labelOf(it, files)) } }
            Work(
                token = d.key,
                platform = d.platform,
                title = d.title,
                author = d.author,
                publishTimeMs = d.publishTimeMs,
                dir = d.dir,
                files = items,
                typeLabel = typeLabelOf(items),
                coverPath = items.firstOrNull { it.isImage }?.path
                    ?: items.firstOrNull { it.isVideo }?.path.orEmpty(),
                coverUrl = d.coverUrl
            )
        }
    }

    /** 旧版平铺文件的归并键：所在目录 + 文案 */
    private fun legacyKey(path: String, token: String): String =
        "L|" + File(path).parent + "|" + titleOf(path, token)

    /**
     * 扫保存目录（根 / 平台 / 博主 / 作品目录 / 文件，共 4 层），把记录没登记的文件归进作品：
     * - 新版排版：按作品标识，和记录里的作品自然合并（视频封面、实况静态原图就是这样补进来的）；
     * - 旧版平铺：按「所在目录 + 文案」归并，理由见 [build]。
     *
     * 文案从目录名 / 旧文件名还原，作者与平台从路径还原，发布时间取文件修改时间——
     * 本应用落盘时把修改时间写成了发布时间。
     */
    private fun scanRoot(root: File, drafts: LinkedHashMap<String, Draft>) {
        val files = runCatching {
            root.walkTopDown().maxDepth(4).filter { it.isFile }.toList()
        }.getOrDefault(emptyList())
        files.forEach { f ->
            val name = f.name
            val token = WorkLayout.tokenOf(name) ?: return@forEach
            val publish = runCatching { f.lastModified() }.getOrDefault(0L)
            val key = if (WorkLayout.isLegacyName(name)) legacyKey(f.absolutePath, token) else token
            val d = drafts.getOrPut(key) { Draft(key, WorkLayout.isLegacyName(name)) }
            val path = f.absolutePath
            if (d.paths.putIfAbsent(path, "" to 0L) != null) return@forEach
            d.sizes[path] = runCatching { f.length() }.getOrDefault(0L)
            if (d.dir.isBlank()) d.dir = f.parent.orEmpty()
            if (d.publishTimeMs == 0L) d.publishTimeMs = publish
            if (d.title.isBlank()) d.title = titleOf(path, token)
            val segments = path.split('/')
            val platform = Platform.values().firstOrNull { segments.contains(it.folderName) }
            if (platform != null && d.platform == Platform.OTHER) d.platform = platform
            if (d.author.isBlank()) d.author = authorOf(d.platform, path, token)
        }
    }

    /** 详情页里一个文件的说明文字 */
    fun labelOf(file: Item, siblings: List<Item>): String = when {
        WorkLayout.mediaClassOf(file.name) == "audio" -> "背景音乐"
        file.isVideo ->
            if (file.partNo > 0 && siblings.any { it.isImage && it.partNo == file.partNo }) "动图"
            else if (file.partNo > 1) "视频 ${file.partNo}" else "视频"
        // 视频的封面：同序号没有视频（单个视频不编号，封面是第 1 张），就叫「封面」
        file.isCover && siblings.none { it.isVideo && it.partNo == file.partNo } -> "封面"
        file.partNo > 0 -> "图片 ${file.partNo}"
        else -> "图片"
    }

    /** 卡片类型角标 */
    fun typeLabelOf(files: List<Item>): String {
        val hasLive = files.any { f -> f.isVideo && f.partNo > 0 && files.any { it.isImage && it.partNo == f.partNo } }
        val images = files.count { it.isImage }
        return when {
            hasLive -> "动图"
            files.any { it.isVideo } -> "视频"
            images > 1 -> "图集"
            images == 1 -> "图片"
            else -> "音乐"
        }
    }

    /**
     * 从文件路径还原文案：文件在作品目录里就取目录名（去掉日期与标识）；
     * 旧版平铺文件名形如 `20261117_文案_5_m<标识>_20261124_175314.mp3`，去掉日期、批次序号、
     * 标识与下载时间。
     */
    fun titleOf(path: String, token: String): String {
        val file = File(path)
        file.parentFile?.name?.takeIf { WorkLayout.tokenOf(it) == token }
            ?.let { return WorkLayout.descFromFolder(it) }
        return file.name
            .replace(Regex("^\\d{8}_"), "")
            .substringBefore("_m${token}_")
            .replace(Regex("_\\d+$"), "")
            .ifBlank { file.name.substringBeforeLast('.') }
    }

    /**
     * 从文件路径还原作者：<保存路径>/<平台目录>/<作者>/<作品目录>/<文件> 取平台目录下第一层；
     * 文件直接放在平台目录里（单链接下载）或「按平台分文件夹」关闭时没有作者，返回空串。
     * 旧版平铺文件在 <平台目录>/<作者>/ 下，上一层就是作者。
     */
    fun authorOf(platform: Platform, path: String, token: String): String {
        val segments = path.split('/')
        val platformIndex = segments.lastIndexOf(platform.folderName)
        if (platformIndex < 0) return ""
        // 平台目录与文件之间隔了几层目录
        return when (segments.size - platformIndex - 2) {
            1 -> File(path).parentFile?.name?.takeIf { WorkLayout.tokenOf(it) != token }.orEmpty()
            2 -> segments[platformIndex + 1]
            else -> ""
        }
    }
}
