package com.lightchat.core

/**
 * Compact text encoding: ASCII = 1 byte (as is), common Persian letters/punctuation/digits = 1 byte
 * (0x80..), anything else = 0xFF + its UTF-8 bytes. Persian text is about half as long as UTF-8,
 * which means half the transmission time.
 */
object TextCodec {
    private const val PERSIAN =
        "اآأؤإئءبپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیةكي‌،؛؟«»٪…–" + "۰۱۲۳۴۵۶۷۸۹"
    private const val BASE = 0x80
    private const val ESC = 0xFF

    private val toByte = HashMap<Int, Int>()

    init {
        var i = 0
        while (i < PERSIAN.length) {
            toByte[PERSIAN[i].code] = BASE + i
            i++
        }
        check(BASE + PERSIAN.length <= ESC) { "charset too big" }
    }

    fun encode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) {
                out.write(cp)
            } else {
                val b = toByte[cp]
                if (b != null) {
                    out.write(b)
                } else {
                    out.write(ESC)
                    val u = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                    out.write(u, 0, u.size)
                }
            }
        }
        return out.toByteArray()
    }

    class Decoded(val text: String, val consumed: Int)

    /** Decodes as many complete symbols as possible from the first [len] bytes. */
    fun decodePrefix(bytes: ByteArray, len: Int = bytes.size): Decoded {
        val sb = StringBuilder()
        var i = 0
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                sb.append(b.toChar())
                i++
            } else if (b == ESC) {
                if (i + 1 >= len) break
                val f = bytes[i + 1].toInt() and 0xFF
                val n = when {
                    f >= 0xF0 -> 4
                    f >= 0xE0 -> 3
                    f >= 0xC0 -> 2
                    else -> 1
                }
                if (i + 1 + n > len) break
                if (n == 1) {
                    sb.append('�')
                } else {
                    sb.append(String(bytes, i + 1, n, Charsets.UTF_8))
                }
                i += 1 + n
            } else {
                val idx = b - BASE
                sb.append(if (idx < PERSIAN.length) PERSIAN[idx] else '?')
                i++
            }
        }
        return Decoded(sb.toString(), i)
    }
}
