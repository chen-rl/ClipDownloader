package com.clipdownloader.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 作品发布时间的归一与展示。纯 Kotlin、不依赖 Android，便于单元测试。
 *
 * 各平台接口给的时间单位不一：抖音、B站为秒，个别平台为毫秒，入口统一走 [toMillis] 归一；
 * 未知一律用 0 表示，所有展示函数对 0 都返回空串（不显示、不加前缀）。
 * 日期一律按设备本地时区格式化。
 */
object PublishTime {

    /** 10 位以内的秒级时间戳上限（约公元 5138 年）；不小于它的只可能是毫秒 */
    private const val MAX_SECONDS = 100_000_000_000L

    /** 合理的毫秒时间戳上限（约公元 33658 年），超过视为脏数据 */
    private const val MAX_MILLIS = MAX_SECONDS * 1000

    /** EXIF 日期字段的格式（本地时间，不带时区） */
    const val EXIF_FORMAT = "yyyy:MM:dd HH:mm:ss"

    private const val DAY_FORMAT = "yyyy-MM-dd"

    /**
     * 平台给的发布时间戳 → 毫秒。10 位整数按秒处理并乘 1000，13 位的毫秒原样返回；
     * 0、负数、超出合理范围的返回 0（未知），不让脏数据变成一个离谱的日期。
     */
    fun toMillis(raw: Long): Long = when {
        raw <= 0 -> 0L
        raw < MAX_SECONDS -> raw * 1000
        raw < MAX_MILLIS -> raw
        else -> 0L
    }

    /** 文件名用的发布日期前缀：`yyyyMMdd_`；未知为空串 */
    fun filePrefix(ms: Long): String = if (ms <= 0) "" else format(DAY_FORMAT, ms).replace("-", "") + "_"

    /** 展示用日期：`yyyy-MM-dd`；未知为空串 */
    fun dateText(ms: Long): String = if (ms <= 0) "" else format(DAY_FORMAT, ms)

    /** 写入图片 EXIF 的拍摄时间：`yyyy:MM:dd HH:mm:ss`；未知为空串 */
    fun exifStamp(ms: Long): String = if (ms <= 0) "" else format(EXIF_FORMAT, ms)

    /**
     * 一批条目的发布时间说明（按「天」去重后取首尾）：
     * - 全部未知 → 空串；
     * - 都在同一天 → `发布 2026-11-17`；
     * - 跨多天（主页批量：从置顶的老作品到最新作品）→ `发布 2025-10-09 ~ 2026-11-17`。
     */
    fun rangeText(times: List<Long>): String {
        val days = times.asSequence().filter { it > 0 }.map { dateText(it) }.distinct().sorted().toList()
        if (days.isEmpty()) return ""
        val first = days.first()
        val last = days.last()
        return if (first == last) "发布 $first" else "发布 $first ~ $last"
    }

    /** SimpleDateFormat 非线程安全：下载可能多批次并发，每次新建（调用量很小） */
    private fun format(pattern: String, ms: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
