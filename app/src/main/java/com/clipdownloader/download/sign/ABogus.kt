package com.clipdownloader.download.sign

import kotlin.random.Random

/** 抖音网页端请求参数 a_bogus 的纯 Kotlin 实现（对应 bdms.js v1.0.1.19-fix.01）。
 *
 * 移植自 Evil0ctal/Douyin_TikTok_Download_API 的 src/dtk/signing/native/abogus.py
 * （Apache-2.0，许可见仓库根目录 THIRD_PARTY_NOTICES.md 与 licenses/Apache-2.0）。
 * 算法分三步：
 *  1. 对 query、body、User-Agent 各做一条 SM3 摘要链，连同 50 个环境字段拼成载荷；
 *  2. 载荷按 3 字节一组混入噪声位，再与非标准 RC4 的密钥流异或；
 *  3. 结果用自定义字母表 base64 编码。
 *
 * 除 [random] 外无状态、开销极小（单次约几毫秒）。[userAgent] 必须与请求实际发送的
 * User-Agent 一致——第三条摘要链与其绑定。
 */
class ABogus(
    private val userAgent: String,
    private val browserInfo: String = DEFAULT_BROWSER_INFO,
    private val pageId: Int = PAGE_ID,
    private val aid: Int = AID,
    /** 噪声源，返回 [0,1) 的随机数；测试时可注入固定序列 */
    private val random: () -> Double = { Random.nextDouble() },
) {

    /** 五十个标量字段，下标即字段名里的数字（L24 → [24]） */
    internal fun fields(query: String, body: String, nowMs: Long): IntArray {
        val digestQuery = digestOf(query)
        val digestBody = digestOf(body)
        val digestUa = userAgentDigest(userAgent)

        // ink = Date.now() - 1：SDK 提前一次调用种在原型上的存活检查
        val ink = nowMs - 1
        val fortnights = ((nowMs - FORTNIGHT_EPOCH_MS) / (1000L * 60 * 60 * 24 * 14)).toInt()
        val infoBytes = jsBytes(browserInfo)
        val tailBytes = jsBytes(tailText(nowMs))

        val f = IntArray(86)
        f[24] = 41
        f[26] = fortnights
        f[27] = CALL_BUCKET
        // SDK 初始化至今的毫秒数加三：常量 3 表示入口点被即时到达
        f[28] = 3
        f[35] = ENV_FLAGS and 0xFF
        f[36] = (ENV_FLAGS ushr 8) and 0xFF
        f[38] = NR_FLAGS and 0xFF
        f[39] = (NR_FLAGS ushr 8) and 0xFF
        f[66] = TRIPWIRE_LOCKED
        f[79] = infoBytes.size and 0xFF
        f[80] = (infoBytes.size ushr 8) and 0xFF
        f[84] = tailBytes.size and 0xFF
        f[85] = (tailBytes.size ushr 8) and 0xFF
        putLittleEndian(f, 29, nowMs, 6)
        putLittleEndian(f, 44, DETECT_FLAGS.toLong(), 4)
        for (index in NR_TAG.indices) f[40 + index] = NR_TAG[index]
        putLittleEndian(f, 60, ink, 6)
        putLittleEndian(f, 67, pageId.toLong(), 4)
        putLittleEndian(f, 71, aid.toLong(), 4)
        // 三条哈希链各贡献三个字节
        writeChain(f, digestQuery, 48, 49, 51, 9, 18, 3, 11, 12)
        writeChain(f, digestBody, 52, 53, 55, 10, 19, 4, 8, 9)
        writeChain(f, digestUa, 56, 57, 59, 11, 21, 5, 12, 13)
        return f
    }

    /**
     * 计算一次请求的 a_bogus 值（未做 URL 百分号编码，发送前需编码）。
     *
     * @param query 即将发送的 query 字符串，不含 a_bogus 本身
     * @param body POST 请求体字符串，GET 留空；签名时为空而实际发送非空即错误签名
     * @param contentType 请求为 multipart/form-data 时 SDK 在哈希前置空 body
     * @param nowMs 毫秒时钟，测试时可固定
     */
    fun getValue(
        query: String,
        body: String = "",
        contentType: String = "",
        nowMs: Long? = null,
    ): String {
        val effectiveBody = if (contentType.contains("multipart/form-data", ignoreCase = true)) "" else body
        val now = nowMs ?: System.currentTimeMillis()
        require(now >= FORTNIGHT_EPOCH_MS) { "clock is before the a_bogus epoch: $now" }
        val f = fields(query, effectiveBody, now)

        // 四个噪声字节中有三个是环境报告而非噪声，仅版本块低字节为自由抽样。
        // 随机数的调用顺序与 SDK 一致：先取两个环境报告值，再取本对的噪声
        val version = maskPair(SDK_VERSION[0], SDK_VERSION[1], null, null) +
            maskPair(SDK_VERSION[2], SDK_VERSION[3], probeNoise(), tripwireNoise())

        // 校验和只覆盖版本块与五十个标量
        var checksum = 0
        for (byte in version) checksum = checksum xor byte
        for (name in FIELD_ORDER) checksum = checksum xor f[name]

        val bodyBytes = IntArray(
            FIELD_ORDER.size + jsBytes(browserInfo).size + jsBytes(tailText(now)).size + 1
        )
        var pos = 0
        for (name in FIELD_ORDER) bodyBytes[pos++] = f[name]
        for (b in jsBytes(browserInfo)) bodyBytes[pos++] = b.toInt() and 0xFF
        for (b in jsBytes(tailText(now))) bodyBytes[pos++] = b.toInt() and 0xFF
        bodyBytes[pos] = checksum

        val header = maskPair(HEADER_MAGIC[0], HEADER_MAGIC[1], null, headerNoise(userAgent))
        val frame = expandNoise(bodyBytes)
        val sealed = rc4(byteArrayOf(PAYLOAD_KEY.toByte()), toBytes(version + frame))
        return encodeBase64(toBytes(header) + sealed, ALPHABET_S4)
    }

    // ==================== 内部步骤 ====================

    /** 载荷尾部文本：(当前毫秒 + 3) 的低八位的十进制再加逗号 */
    private fun tailText(nowMs: Long): String = "${(nowMs + 3) and 0xFF},"

    /** 按 SDK 的 charCodeAt 规则把字符串转为字节：逐个 UTF-16 码元，低于 U+0100 输出一字节，否则输出两个大端字节 */
    internal fun jsBytes(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        var n = 0
        for (ch in text) {
            val code = ch.code
            if (code and 0xFF00 != 0) out[n++] = (code ushr 8).toByte()
            out[n++] = (code and 0xFF).toByte()
        }
        return out.copyOf(n)
    }

    /** SM3(SM3(text + SALT))，共 32 字节 */
    internal fun digestOf(text: String): ByteArray =
        Sm3.hash(Sm3.hash((text + SALT).toByteArray(Charsets.UTF_8)))

    /** 第三条链：RC4 加密 User-Agent，s3 字母表 base64 编码后做一次 SM3 */
    internal fun userAgentDigest(ua: String): ByteArray {
        val key = byteArrayOf(
            (ENV_FLAGS ushr 8).toByte(),
            (ENV_FLAGS and 0xFF).toByte(),
            (DETECT_FLAGS and 0xFF).toByte(),
        )
        // 以 charCodeAt 未掩码的值逐码元推进，后续 base64 掩码到低八位
        val sealed = rc4(key, jsBytes(ua.trim()))
        return Sm3.hash(encodeBase64(sealed, ALPHABET_S3).toByteArray(Charsets.UTF_8))
    }

    /** digest[offset:] 起第一个不等于 sentinel 的字节，没有则取 fallback */
    private fun canary(digest: ByteArray, offset: Int, sentinel: Int, fallback: Int): Int {
        for (index in offset until digest.size) {
            val byte = digest[index].toInt() and 0xFF
            if (byte != sentinel) return byte
        }
        return fallback
    }

    private fun writeChain(
        f: IntArray, digest: ByteArray,
        s0: Int, s1: Int, s2: Int, i0: Int, i1: Int,
        cOffset: Int, cSentinel: Int, cFallback: Int,
    ) {
        f[s0] = digest[i0].toInt() and 0xFF
        f[s1] = digest[i1].toInt() and 0xFF
        f[s2] = canary(digest, cOffset, cSentinel, cFallback)
    }

    /** value 的 count 个小端字节写入 f[start..start+count) */
    private fun putLittleEndian(f: IntArray, start: Int, value: Long, count: Int) {
        for (index in 0 until count) f[start + index] = ((value ushr (8 * index)) and 0xFF).toInt()
    }

    /** UA 家族分桶：Edge 与华为浏览器均含 "Chrome"，Chrome 又含 "Safari"，先特定后通用 */
    private fun headerNoiseBase(ua: String): Int {
        val lower = ua.lowercase()
        return when {
            "edg" in lower -> 125
            "huawei" in lower -> 170
            "firefox" in lower -> 40
            "chrome" in lower -> 0
            "safari" in lower -> 81
            else -> 210
        }
    }

    /** 伪装成噪声的浏览器家族报告 */
    private fun headerNoise(ua: String): Int = (headerNoiseBase(ua) + (random() * 40).toInt()) and 0xFF

    /** 超过 109 时强制为奇数 */
    private fun probeNoise(): Int {
        val value = (random() * 240).toInt()
        return if (value > 109) value + value % 2 + 1 else value
    }

    /** "绊线全部存在且已锁定"，叠加真实噪声 */
    private fun tripwireNoise(): Int = ((random() * 255).toInt() and TRIPWIRE_FREE) or TRIPWIRE_SET

    /**
     * 把两个字节摊到四个字节上，另一半填噪声。
     * 每个输出字节的一半来自载荷（掩码 0xAA/0x55 交替），一半来自噪声；
     * [low]/[high] 为 null 时取 SDK 的普通噪声抽样。噪声随机数总是先取一次。
     */
    private fun maskPair(p0: Int, p1: Int, low: Int?, high: Int?): IntArray {
        val noise = (random() * 65535).toInt()
        val lo = (low ?: noise) and 0xFF
        val hi = (high ?: (noise ushr 8)) and 0xFF
        return intArrayOf(
            (lo and 0xAA) or (p0 and 0x55),
            (lo and 0x55) or (p0 and 0xAA),
            (hi and 0xAA) or (p1 and 0x55),
            (hi and 0x55) or (p1 and 0xAA),
        )
    }

    /** 三个数据字节与一个噪声字节混成四个载体字节，第四个字节恰好收集前三个让出的比特 */
    private fun expandNoise(body: IntArray): IntArray {
        val out = IntArray((body.size / 3) * 4 + 2)
        var n = 0
        var offset = 0
        while (offset < body.size) {
            val remain = body.size - offset
            if (remain < 3) {
                // SDK 的尾部分支（本实现的载荷长度恒为 3 的倍数，仅为对齐保留）
                out[n++] = body[offset]
                if (remain > 1 && body[offset + 1] != 0) out[n++] = body[offset + 1]
                break
            }
            val g0 = body[offset]
            val g1 = body[offset + 1]
            val g2 = body[offset + 2]
            val noise = (random() * 1000).toInt() and 0xFF
            out[n++] = (noise and NOISE_MASKS[0]) or (g0 and DATA_MASKS[0])
            out[n++] = (noise and NOISE_MASKS[1]) or (g1 and DATA_MASKS[1])
            out[n++] = (noise and NOISE_MASKS[2]) or (g2 and DATA_MASKS[2])
            out[n++] = (g0 and NOISE_MASKS[0]) or (g1 and NOISE_MASKS[1]) or (g2 and NOISE_MASKS[2])
            offset += 3
        }
        return out.copyOf(n)
    }

    /** SDK 的 RC4：S 盒降序初始化，密钥调度使用乘法 */
    internal fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val box = IntArray(256)
        for (i in 0 until 256) box[255 - i] = i
        var j = 0
        for (i in 0 until 256) {
            j = (j * box[i] + j + (key[i % key.size].toInt() and 0xFF)) % 256
            val t = box[i]
            box[i] = box[j]
            box[j] = t
        }
        val out = ByteArray(data.size)
        // 伪随机生成阶段从 i = j = 0 重新开始，不复用密钥调度结束时的 j
        var i = 0
        j = 0
        for (index in data.indices) {
            i = (i + 1) % 256
            j = (j + box[i]) % 256
            val t = box[i]
            box[i] = box[j]
            box[j] = t
            out[index] = (data[index].toInt() xor box[(box[i] + box[j]) % 256]).toByte()
        }
        return out
    }

    /** SDK 字母表上的 base64 编码，字面量 "=" 填充 */
    internal fun encodeBase64(data: ByteArray, alphabet: String): String {
        val sb = StringBuilder()
        var offset = 0
        while (offset < data.size) {
            val n = minOf(3, data.size - offset)
            var block = 0
            for (k in 0 until 3) {
                val b = if (k < n) data[offset + k].toInt() and 0xFF else 0
                block = (block shl 8) or b
            }
            val shifts = intArrayOf(18, 12, 6, 0)
            for (k in 0..n) sb.append(alphabet[(block ushr shifts[k]) and 0x3F])
            offset += 3
        }
        val pad = (4 - sb.length % 4) % 4
        repeat(pad) { sb.append('=') }
        return sb.toString()
    }

    private fun toBytes(values: IntArray): ByteArray = ByteArray(values.size) { values[it].toByte() }

    companion object {
        /** a_bogus 使用的两套字母表：s4 编码最终签名，s3 编码进入第三个摘要的 User-Agent */
        const val ALPHABET_S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
        const val ALPHABET_S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"

        /** 哈希前附加在 query 与 body 末尾的盐值 */
        private const val SALT = "dhzx"

        /** 签名头部的两个明文字节（相当于格式魔数） */
        private val HEADER_MAGIC = intArrayOf(3, 82)

        /** "1.0.1.19-fix.01" 解析后的版本块 */
        private val SDK_VERSION = intArrayOf(1, 0, 1, 0)

        /** 载荷密钥（单字节）。该 RC4 并非标准 RC4 */
        private const val PAYLOAD_KEY = 0xD3

        /** 2024-07-24T16:00:00Z，其中一个字段自此以双周为单位计数 */
        private const val FORTNIGHT_EPOCH_MS = 1_721_836_800_000L

        /** 抖音网页端的固定标识，随签名与 query 一起发送，两者必须一致 */
        const val PAGE_ID = 6241
        const val AID = 6383

        /** 三个掩码把三个数据字节与一个噪声字节混成四个载体字节 */
        private val NOISE_MASKS = intArrayOf(0x91, 0x42, 0x2C)
        private val DATA_MASKS = intArrayOf(0x6E, 0xBD, 0xD3)

        /** 五十个标量字段在载荷中的固定排列顺序 */
        private val FIELD_ORDER = intArrayOf(
            34, 44, 56, 61, 73, 29, 70, 45, 35, 49,
            38, 66, 51, 68, 28, 48, 64, 47, 30, 71,
            26, 55, 31, 69, 59, 40, 62, 63, 27, 72,
            41, 74, 57, 52, 42, 39, 33, 67, 53, 43,
            65, 46, 36, 24, 60, 32, 79, 80, 84, 85,
        )

        /** 未被篡改的浏览器环境报告值 */
        private const val ENV_FLAGS = 1
        private const val DETECT_FLAGS = 14
        private const val NR_FLAGS = 0x21
        private val NR_TAG = intArrayOf(0, 0, 0, 0)

        /** window.onwheelx._Ax 存在且已锁定——SDK 自装的正常状态 */
        private const val TRIPWIRE_LOCKED = 3

        /** 签名次数分桶：6 表示本页签名次数少于 140 次 */
        private const val CALL_BUCKET = 6

        /**
         * Chrome 桌面端 1080p 屏幕的几何信息（内窗|外窗|可用区|屏幕|平台）。
         * 无屏幕信息时用固定默认值优于随机值：随请求变化的几何信息本身就是特征。
         * 与请求参数里的 Windows / Win32 / 1920×1080 身份保持一致。
         */
        const val DEFAULT_BROWSER_INFO = "1920|947|1920|1032|1920|1032|1920|1080|Win32"

        /** 绊线探测值：位 1、4、5、7 置位是健康状态 */
        private const val TRIPWIRE_SET = 0xB2
        private const val TRIPWIRE_FREE = 0x4D
    }
}
