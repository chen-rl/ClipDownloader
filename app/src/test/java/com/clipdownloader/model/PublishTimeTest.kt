package com.clipdownloader.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 发布时间归一与格式化的单元测试。
 *
 * 期望值按 JVM 默认时区用同样的模式现算，测试在任何时区的机器上都成立；
 * 另有一组在东八区下才断言的固定值，样本是核对过的真实数据：
 * 抖音作品 7711285224621801435 的 create_time = 1795423502（秒）= 2026-11-23 16:45:02。
 */
class PublishTimeTest {

    private fun fmt(pattern: String, timeMs: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timeMs))

    private val sec = 1_795_423_502L
    private val ms = sec * 1000

    @Test
    fun toMillis_normalizesSecondsAndMilliseconds() {
        assertEquals(0L, PublishTime.toMillis(0))
        assertEquals(0L, PublishTime.toMillis(-5))
        // 10 位按秒处理
        assertEquals(ms, PublishTime.toMillis(sec))
        // 13 位已是毫秒，原样返回
        assertEquals(ms, PublishTime.toMillis(ms))
        // 脏数据：既不是合理的秒也不是合理的毫秒，按未知处理而不是变成离谱的日期
        assertEquals(0L, PublishTime.toMillis(100_000_000_000_000L))
        assertEquals(0L, PublishTime.toMillis(Long.MAX_VALUE))
        // 合理毫秒的上界（含）仍然有效
        assertEquals(99_999_999_999_999L, PublishTime.toMillis(99_999_999_999_999L))
    }

    @Test
    fun dateText_andExifStamp_useLocalTimeZone() {
        assertEquals("", PublishTime.dateText(0))
        assertEquals("", PublishTime.exifStamp(0))
        assertEquals(fmt("yyyy-MM-dd", ms), PublishTime.dateText(ms))
        assertEquals(fmt("yyyy:MM:dd HH:mm:ss", ms), PublishTime.exifStamp(ms))
        if (TimeZone.getDefault().getOffset(ms) == 8 * 3_600_000) {
            assertEquals("2026-11-23", PublishTime.dateText(ms))
            assertEquals("2026:11:23 16:45:02", PublishTime.exifStamp(ms))
        }
    }

    @Test
    fun rangeText_singleDayOrRange() {
        assertEquals("", PublishTime.rangeText(emptyList()))
        assertEquals("", PublishTime.rangeText(listOf(0L, 0L)))
        val day = PublishTime.dateText(ms)
        val laterDay = PublishTime.dateText(ms + 3 * 86_400_000L)
        // 单个作品（图集多张共用同一时间）
        assertEquals("发布 $day", PublishTime.rangeText(listOf(ms)))
        // 同一天 + 夹着未知
        assertEquals("发布 $day", PublishTime.rangeText(listOf(ms, ms + 60_000L, 0L)))
        // 跨多天（主页批量）：输入倒序也按日期先后排列
        assertEquals("发布 $day ~ $laterDay", PublishTime.rangeText(listOf(ms + 3 * 86_400_000L, 0L, ms)))
        assertEquals("发布 $day ~ $laterDay", PublishTime.rangeText(listOf(ms, ms + 3 * 86_400_000L)))
    }
}
