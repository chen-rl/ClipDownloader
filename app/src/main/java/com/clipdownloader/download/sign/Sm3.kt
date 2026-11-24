package com.clipdownloader.download.sign

/**
 * 国密 SM3 哈希（GB/T 32905-2016），纯 Kotlin 实现，无第三方依赖。
 *
 * a_bogus 的三条摘要链都基于它。移植自 Evil0ctal/Douyin_TikTok_Download_API 的
 * src/dtk/signing/native/sm3.py（Apache-2.0），输出为 32 字节摘要。
 */
object Sm3 {

    private val IV = intArrayOf(
        0x7380166F, 0x4914B2B9, 0x172442D7, 0xDA8A0600.toInt(),
        0xA96F30BC.toInt(), 0x163138AA, 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt()
    )
    private const val T0 = 0x79CC4519
    private val T1 = 0x7A879D8A.toInt()

    /** 32 位循环左移；移位数为 0 时直接返回（Kotlin 的 ushr 对 32 取模，不能直接移 32 位） */
    private fun rotl(value: Int, bits: Int): Int {
        val b = ((bits % 32) + 32) % 32
        if (b == 0) return value
        return (value shl b) or (value ushr (32 - b))
    }

    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    /** 消息扩展：返回 68 个 W 与 64 个 W'（全部按 32 位字运算，溢出即取模） */
    private fun expand(block: ByteArray): Pair<IntArray, IntArray> {
        val w = IntArray(68)
        for (i in 0 until 16) {
            val p = i * 4
            w[i] = ((block[p].toInt() and 0xFF) shl 24) or ((block[p + 1].toInt() and 0xFF) shl 16) or
                ((block[p + 2].toInt() and 0xFF) shl 8) or (block[p + 3].toInt() and 0xFF)
        }
        for (i in 16 until 68) {
            var x = w[i - 16] xor w[i - 9] xor rotl(w[i - 3], 15)
            x = x xor rotl(x, 15) xor rotl(x, 23)
            w[i] = x xor rotl(w[i - 13], 7) xor w[i - 6]
        }
        val w1 = IntArray(64) { w[it] xor w[it + 4] }
        return w to w1
    }

    /** 压缩函数：state 与分组运算后异或，Int 加法自然按 2^32 取模 */
    private fun compress(state: IntArray, block: ByteArray): IntArray {
        val (w, w1) = expand(block)
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (j in 0 until 64) {
            val t = if (j < 16) T0 else T1
            val ss1 = rotl(rotl(a, 12) + e + rotl(t, j), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = ff(j, a, b, c) + d + ss2 + w1[j]
            val tt2 = gg(j, e, f, g) + h + ss1 + w[j]
            d = c
            c = rotl(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl(f, 19)
            f = e
            e = tt2 xor rotl(tt2, 9) xor rotl(tt2, 17)
        }
        val v = intArrayOf(a, b, c, d, e, f, g, h)
        return IntArray(8) { state[it] xor v[it] }
    }

    /** 填充：原文 + 0x80 + 若干 0x00（凑到 56 mod 64）+ 8 字节大端比特长度 */
    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val zeroCount = (56 - (message.size + 1) % 64 + 64) % 64
        val out = ByteArray(message.size + 1 + zeroCount + 8)
        message.copyInto(out)
        out[message.size] = 0x80.toByte()
        for (i in 0 until 8) out[out.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        return out
    }

    /** 返回 message 的 32 字节 SM3 摘要 */
    fun hash(message: ByteArray): ByteArray {
        var state = IV
        val padded = pad(message)
        var offset = 0
        while (offset < padded.size) {
            state = compress(state, padded.copyOfRange(offset, offset + 64))
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (state[i] ushr 24).toByte()
            out[i * 4 + 1] = (state[i] ushr 16).toByte()
            out[i * 4 + 2] = (state[i] ushr 8).toByte()
            out[i * 4 + 3] = state[i].toByte()
        }
        return out
    }

    /** [hash] 的十六进制形式（小写，64 个字符） */
    fun hexDigest(message: ByteArray): String = SignHex.toHex(hash(message))
}
