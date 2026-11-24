package com.clipdownloader.download.sign

import java.security.MessageDigest

/** 签名用到的字节数组 ↔ 十六进制转换（小写），无第三方依赖。 */
internal object SignHex {

    private const val DIGITS = "0123456789abcdef"

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** x-secsdk-web-signature 用 MD5；标准库 MessageDigest 即可 */
internal fun md5Hex(text: String): String =
    SignHex.toHex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))
