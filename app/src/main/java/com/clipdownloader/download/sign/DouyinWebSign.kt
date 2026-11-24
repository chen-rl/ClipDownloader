package com.clipdownloader.download.sign

import java.security.MessageDigest

/**
 * 抖音网页端「签名保护接口」的请求签名（x-secsdk-web-signature），纯 Kotlin 实现。
 *
 * 移植自 Evil0ctal/Douyin_TikTok_Download_API 的 src/dtk/signing/native/websign.py
 * （Apache-2.0，许可见仓库根目录 THIRD_PARTY_NOTICES.md 与 licenses/Apache-2.0）。
 * 对应抖音 secsdk（runtime_bundler_34.js，@byted/secsdk-strategy v1.0.40）注册的 webSignUrl。
 *
 * 抖音并非对所有接口做签名保护；受保护的接口（含主页作品 /aweme/v1/web/aweme/post/）
 * 请求缺少签名时返回 `403 Blocked by ArgusSecurityPlugin Signature Not Found`。
 * 签名 = MD5(uifid_timestamp_盐_query)，其中 query 是它前面的完整参数（含 a_bogus）按
 * [encodePairs] 序列化后的字节序本身——预映像与实际发送的字节必须一致。
 */
object DouyinWebSign {

    /** douyin_web 项目的盐值（www.douyin.com 以 project-id="34" 加载的 SDK 内的字符串） */
    const val SALT = "A96D855A08C0A9707F8BEF0D9A527E4E"

    const val SIGNATURE_PARAM = "x-secsdk-web-signature"
    const val UIFID_PARAM = "uifid"
    const val TIMESTAMP_PARAM = "timestamp"

    /** Python urllib.parse.quote 永不编码的字符 */
    private const val ALWAYS_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~"
    private const val HEX_UPPER = "0123456789ABCDEF"

    /**
     * 等价于参考实现的 urllib.parse.quote(text, safe)：ASCII 字节中属于 [ALWAYS_SAFE] 或 [safe] 的原样输出，
     * 其余字节按 UTF-8 逐个 %XX（大写十六进制）。
     */
    fun quote(text: String, safe: String = "*-._"): String {
        val sb = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && (ALWAYS_SAFE.indexOf(c) >= 0 || safe.indexOf(c) >= 0)) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX_UPPER[v ushr 4]).append(HEX_UPPER[v and 0x0F])
            }
        }
        return sb.toString()
    }

    /** 按参考实现的规则把键值对序列化成 query：名字与值各做一次 [quote] */
    fun encodePairs(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (name, value) -> "${quote(name)}=${quote(value)}" }

    /** 一次签名的产物 */
    data class Result(
        /** 最终 query，以 `&x-secsdk-web-signature={signature}` 结尾，可原样拼在 URL 后发送 */
        val query: String,
        val signature: String,
        val uifid: String,
        /** 秒级时间戳的字符串形式 */
        val timestamp: String,
    )

    /**
     * 对 [pairs] 追加时间戳并计算 x-secsdk-web-signature。
     *
     * [pairs] 里的值必须是**未做百分号编码的原始值**：本函数会用 [encodePairs] 把全部值各编码一次，
     * 哈希的字节与发送的字节因此天然一致。传入已编码的值会被二次编码，得到平台不认的签名。
     *
     * @param pairs 签名之前的全部参数，按发送顺序：业务参数，其后是 a_bogus 层追加的参数
     * @param uifid 签名绑定的身份 ID
     * @param timestamp 秒级时间戳，测试时可固定；缺省取当前时间
     */
    fun sign(pairs: List<Pair<String, String>>, uifid: String, timestamp: Long? = null): Result {
        val stamp = (timestamp ?: System.currentTimeMillis() / 1000).toString()
        val covered = pairs.toMutableList()
        // uifid 已在参数里时保持原位、不重复追加：重复会产生不同的预映像，签名被平台拒绝
        if (covered.none { it.first == UIFID_PARAM }) covered.add(UIFID_PARAM to uifid)
        covered.add(TIMESTAMP_PARAM to stamp)
        val query = encodePairs(covered)
        val signature = md5Hex("${uifid}_${stamp}_${SALT}_$query")
        return Result("$query&$SIGNATURE_PARAM=$signature", signature, uifid, stamp)
    }
}

/**
 * 抖音受签名保护接口的完整签名流程：
 * 业务参数序列化 → a_bogus（发送前百分号编码后追加）→ timestamp + x-secsdk-web-signature。
 *
 * [userAgent] 必须与请求实际发送的 User-Agent 一致（a_bogus 的第三条摘要链与其绑定）。
 */
class DouyinRequestSigner(
    private val userAgent: String,
    random: () -> Double = { kotlin.random.Random.nextDouble() },
) {

    private val abogus = ABogus(userAgent, random = random)

    /** 把业务参数签成可直接拼在 URL 后发送的最终 query */
    fun signProtectedQuery(pairs: List<Pair<String, String>>, uifid: String, nowMs: Long? = null): DouyinWebSign.Result {
        val query = DouyinWebSign.encodePairs(pairs)
        val aBogus = abogus.getValue(query, nowMs = nowMs)
        // sign() 会把所有值各编码一次，这里必须传「原始」a_bogus：预先编码会让 % 被二次编码成 %25，
        // 签名与平台看到的字节不一致（服务端只回空 body，不报错）
        return DouyinWebSign.sign(pairs + ("a_bogus" to aBogus), uifid, nowMs?.let { it / 1000 })
    }
}
