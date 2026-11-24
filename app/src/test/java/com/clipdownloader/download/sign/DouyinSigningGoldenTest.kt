package com.clipdownloader.download.sign

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖音签名移植的黄金向量测试。
 *
 * 向量由 Python 参考实现 Evil0ctal/Douyin_TikTok_Download_API（Apache-2.0）的
 * src/dtk/signing/native/{sm3,abogus,websign}.py 在「固定时钟 + 脚本化随机序列」下生成，
 * 见 src/test/resources/douyin_signing_golden.json（文件内的 _source 记录了参考实现的提交）。
 * Kotlin 实现必须逐位复现这些值。
 *
 * 这套测试抓出过两个真 bug：RC4 伪随机生成阶段未重置 j；a_bogus 被预先编码后又交给 sign() 二次编码。
 * 后者说明黄金向量的局限——向量和实现若出自同一个误解会自洽地全绿，所以除逐位比对外，
 * 这里还断言线上形态（a_bogus 恰好编码一次、query 中不出现 %25）。
 */
class DouyinSigningGoldenTest {

    private val golden: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("douyin_signing_golden.json")
        requireNotNull(stream) { "缺少 douyin_signing_golden.json" }
            .use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    /** 脚本化随机源：依次返回黄金向量里的序列（均为 2 的幂分之，二进制精确），循环取值 */
    private fun scriptedRandom(): () -> Double {
        val seq = golden.getJSONArray("seq")
        val values = DoubleArray(seq.length()) { seq.getDouble(it) }
        var index = 0
        return { values[index++ % values.size] }
    }

    private fun bytesToHex(bytes: ByteArray): String = SignHex.toHex(bytes)

    /** 黄金向量里的 [["k","v"],…] 形式的键值对 */
    private fun pairsOf(key: String): List<Pair<String, String>> {
        val arr: JSONArray = golden.getJSONArray(key)
        return List(arr.length()) { arr.getJSONArray(it).getString(0) to arr.getJSONArray(it).getString(1) }
    }

    private fun ab(): ABogus = ABogus(golden.getString("ua"), random = scriptedRandom())

    private val now: Long by lazy { golden.getLong("now_ms") }

    @Test
    fun sm3_matchesStandardVectors() {
        // GB/T 32905-2016 标准示例值
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", Sm3.hexDigest("abc".toByteArray()))
        assertEquals("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732", Sm3.hexDigest("abcd".repeat(16).toByteArray()))
        // 与参考实现逐一比对：3 字节、64 字节（恰好一个分组，验证填充再补一个完整分组）、空串
        assertEquals(golden.getString("sm3_abc_hex"), Sm3.hexDigest("abc".toByteArray()))
        assertEquals(golden.getString("sm3_abcd_repeat16_hex"), Sm3.hexDigest("abcd".repeat(16).toByteArray()))
        assertEquals(golden.getString("sm3_64a_hex"), Sm3.hexDigest(ByteArray(64) { 'a'.code.toByte() }))
        assertEquals(golden.getString("sm3_empty_hex"), Sm3.hexDigest(ByteArray(0)))
    }

    @Test
    fun digestChains_matchReference() {
        val ab = ab()
        assertEquals(golden.getString("digest_of_query_hex"), bytesToHex(ab.digestOf(golden.getString("query"))))
        assertEquals(golden.getString("digest_of_body_empty_hex"), bytesToHex(ab.digestOf("")))
        // 含非 ASCII（已是百分号编码的 query）同样按 UTF-8 字节哈希
        assertEquals(golden.getString("digest_of_query_cn_hex"), bytesToHex(ab.digestOf(golden.getString("query_cn"))))
        assertEquals(golden.getString("ua_digest_hex"), bytesToHex(ab.userAgentDigest(golden.getString("ua"))))
    }

    @Test
    fun userAgentDigest_intermediateSteps() {
        // UA 摘要链 = RC4 → s3 字母表 base64 → SM3，逐步比对以定位分歧
        val ua = golden.getString("ua")
        val ab = ab()
        val key = byteArrayOf(0, 1, 14)
        assertEquals(golden.getString("rc4_vec_out_hex"), bytesToHex(ab.rc4(key, SignHex.fromHex(golden.getString("rc4_vec_data_hex")))))
        assertEquals(golden.getString("ua_jsbytes_hex"), bytesToHex(ab.jsBytes(ua.trim())))
        val sealed = ab.rc4(key, ab.jsBytes(ua.trim()))
        assertEquals(golden.getString("ua_sealed_hex"), bytesToHex(sealed))
        assertEquals(golden.getString("ua_sealed_b64"), ab.encodeBase64(sealed, ABogus.ALPHABET_S3))
        // 末尾不足 3 字节与 "=" 填充
        val vec = SignHex.fromHex(golden.getString("b64_vec_hex"))
        assertEquals(golden.getString("b64_vec_s4"), ab.encodeBase64(vec, ABogus.ALPHABET_S4))
        assertEquals(golden.getString("b64_vec_s3"), ab.encodeBase64(vec, ABogus.ALPHABET_S3))
    }

    @Test
    fun jsBytes_followCharCodeAtRule() {
        // "抖音" 两个码元各输出两个大端字节，",a" 各一字节，é 输出两个字节
        assertEquals(golden.getString("js_bytes_cn_hex"), bytesToHex(ab().jsBytes("抖音,a\u00e9")))
    }

    @Test
    fun fiftyFields_matchReference() {
        val fieldsJson = golden.getJSONObject("fields")
        val f = ab().fields(golden.getString("query"), "", now)
        assertEquals(50, fieldsJson.length())
        for (name in fieldsJson.keys()) {
            assertEquals("field $name", fieldsJson.getInt(name), f[name.removePrefix("L").toInt()])
        }
    }

    @Test
    fun aBogus_matchesReference() {
        val value = ab().getValue(golden.getString("query"), body = "", nowMs = now)
        assertEquals(golden.getString("a_bogus"), value)
        assertEquals(188, value.length)

        val valueCn = ab().getValue(golden.getString("query_cn"), body = "", nowMs = now)
        assertEquals(golden.getString("a_bogus_cn"), valueCn)
    }

    @Test
    fun aBogus_outputAlphabetAndShape() {
        // s4 字母表 + "=" 填充，长度为 4 的倍数
        val value = ABogus("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .getValue("device_platform=webapp&aid=6383", nowMs = now)
        assertEquals(0, value.length % 4)
        for (c in value.trimEnd('=')) assertTrue("非法字符 $c", ABogus.ALPHABET_S4.indexOf(c) >= 0)
        // 默认 browser_info 与 Windows 身份一致
        assertEquals("1920|947|1920|1032|1920|1032|1920|1080|Win32", ABogus.DEFAULT_BROWSER_INFO)
    }

    @Test
    fun quote_followsReferenceRules() {
        // a_bogus 的 s4 字母表含 / 与 =，safe="" 时分别编码为 %2F、%3D
        assertEquals(golden.getString("a_bogus_raw_wire"), DouyinWebSign.quote(golden.getString("a_bogus"), safe = ""))
        // 默认 safe="*-._"：* ~ 不编码；= / + 空格 需编码（空格是 %20 而不是 +）
        assertEquals("a*b~c%3Dx%2Fy%2Bz", DouyinWebSign.quote("a*b~c=x/y+z"))
        assertEquals("%E6%8A%96%E9%9F%B3%20%E6%B5%8B%E8%AF%95", DouyinWebSign.quote("抖音 测试"))
        // safe="" 时除字母数字与 _.-~ 外全部编码
        assertEquals("%3D%2F%20%3F%26%25%20%22%2B%E4%B8%AD", DouyinWebSign.quote("=/ ?&% \"+中", safe = ""))
        assertEquals("", DouyinWebSign.quote(""))
    }

    @Test
    fun encodePairs_matchesReference() {
        // 中文、空格、*~、=/+、空值
        assertEquals(golden.getString("encode_pairs_special"), DouyinWebSign.encodePairs(pairsOf("pairs_special")))
        assertEquals(golden.getString("query"), DouyinWebSign.encodePairs(pairsOf("pairs")))
    }

    @Test
    fun webSign_matchesReference() {
        // sign() 收未编码的原始 a_bogus，由它内部编码一次
        val result = DouyinWebSign.sign(pairsOf("pairs") + ("a_bogus" to golden.getString("a_bogus")),
            golden.getString("uifid"), golden.getLong("stamp"))
        assertEquals(golden.getString("websign_signature"), result.signature)
        assertEquals(golden.getString("websign_final"), result.query)
        assertEquals(32, result.signature.length)
        assertEquals(golden.getLong("stamp").toString(), result.timestamp)
        // 线上形态：a_bogus 恰好编码一次（二次编码会出现 %25，平台不认且只回空 body）
        assertTrue(result.query.contains("&a_bogus=${golden.getString("a_bogus_raw_wire")}&timestamp="))
        assertFalse("a_bogus 被二次编码", result.query.contains("%25"))
        // 参数里没有 uifid 时补在 timestamp 之前
        val noUifid = DouyinWebSign.sign(listOf("aid" to "6383"), golden.getString("uifid"), golden.getLong("stamp"))
        assertEquals("aid=6383&uifid=${golden.getString("uifid")}&timestamp=${golden.getLong("stamp")}" +
            "&${DouyinWebSign.SIGNATURE_PARAM}=${noUifid.signature}", noUifid.query)
    }

    @Test
    fun endToEnd_signProtectedQueryMatchesReference() {
        val result = DouyinRequestSigner(golden.getString("ua"), random = scriptedRandom())
            .signProtectedQuery(pairsOf("pairs"), golden.getString("uifid"), now)
        assertEquals(golden.getString("websign_final"), result.query)
        assertEquals(golden.getString("websign_signature"), result.signature)
        assertFalse("a_bogus 被二次编码", result.query.contains("%25"))
    }
}
