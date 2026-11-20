package com.lightchat.core

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
object Crc16 {
    fun compute(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (b in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
