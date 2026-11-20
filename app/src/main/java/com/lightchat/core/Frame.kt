package com.lightchat.core

/**
 * One packet on the light channel.
 *
 * Wire bytes: [header][payload 0..15][crc16 hi][crc16 lo]
 *   header = type(2 bits) | len(4 bits) | check(2 bits)   (check detects every single-bit header error)
 *   type: 0 = DATA chunk, 1 = ACK, 2 = CTRL
 *
 * Light channel ("slots" of equal length, torch ON/OFF):
 *   transmission = PREAMBLE  frame  [MARKER frame]*  (torch stays OFF afterwards)
 *   PREAMBLE : 6 x (ON, OFF) one-slot pulses - the receiver measures the slot time from these
 *   MARKER   : ON for 7 slots. Data can never contain an ON run longer than 4 slots, so a marker is
 *              unambiguous. EVERY frame starts with a marker, so the receiver re-synchronises its clock
 *              before each frame and one damaged frame never harms the next ones.
 *   frame    : start bit 1, then the wire bits MSB first, coded as NRZI (bit 1 = level toggles,
 *              bit 0 = level stays) with bit stuffing: after three 0 bits a 1 bit is inserted (and removed
 *              by the receiver), so no run is longer than 4 slots. If the frame ends with the torch ON,
 *              one extra toggle is appended so the next marker starts with a rising edge.
 * This carries about 0.9 bit per slot (Manchester, used before, carried 0.5).
 */
class Frame(val type: Int, val payload: ByteArray)

object FrameCodec {
    const val TYPE_DATA = 0
    const val TYPE_ACK = 1
    const val TYPE_CTRL = 2

    const val MAX_PAYLOAD = 15
    const val PREAMBLE_PULSES = 6

    const val MARKER_SLOTS = 7
    const val MAX_RUN = 4 // longest ON/OFF run inside a frame (slots)

    /**
     * Allowed slot durations (ms), slow to fast. The receiver measures the preamble and snaps to the nearest
     * entry, so both sides share an exact clock grid. (Name kept from the old Manchester version.)
     */
    val HALF_MS_CHOICES: DoubleArray = doubleArrayOf(160.0, 130.0, 100.0, 75.0, 55.0)

    /** A speed is usable when one slot spans at least [SLOT_FRAMES] camera frames. */
    const val SLOT_FRAMES = 3.0
    fun speedSupported(halfMs: Double, cameraFps: Double): Boolean = halfMs >= SLOT_FRAMES * 1000.0 / cameraFps - 1.0

    /** Index of the fastest speed in [HALF_MS_CHOICES] this camera frame rate can read. */
    fun maxSpeedIndex(cameraFps: Double): Int {
        var best = 0
        for (i in HALF_MS_CHOICES.indices) if (speedSupported(HALF_MS_CHOICES[i], cameraFps)) best = i
        return best
    }

    private fun check(type: Int, len: Int): Int = (len xor (len ushr 2) xor type xor 1) and 3

    fun headerOk(h: Int): Boolean {
        val type = (h ushr 6) and 3
        val len = (h ushr 2) and 15
        return type != 3 && (h and 3) == check(type, len)
    }

    fun headerLen(h: Int): Int = (h ushr 2) and 15

    fun encode(frame: Frame): ByteArray {
        val n = frame.payload.size
        require(n <= MAX_PAYLOAD) { "payload too long" }
        require(frame.type in 0..2) { "bad type" }
        val out = ByteArray(1 + n + 2)
        out[0] = ((frame.type shl 6) or (n shl 2) or check(frame.type, n)).toByte()
        System.arraycopy(frame.payload, 0, out, 1, n)
        val crc = Crc16.compute(out, 1 + n)
        out[1 + n] = (crc ushr 8).toByte()
        out[2 + n] = (crc and 0xFF).toByte()
        return out
    }

    /** Returns null when header, length or CRC is wrong. */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < 3) return null
        val h = bytes[0].toInt() and 0xFF
        if (!headerOk(h)) return null
        val n = headerLen(h)
        if (bytes.size != 1 + n + 2) return null
        val crcRx = ((bytes[1 + n].toInt() and 0xFF) shl 8) or (bytes[2 + n].toInt() and 0xFF)
        if (crcRx != Crc16.compute(bytes, 1 + n)) return null
        return Frame((h ushr 6) and 3, bytes.copyOfRange(1, 1 + n))
    }

    /** Slot levels (true = torch ON) of a whole transmission carrying the given wire frames. */
    fun toSlots(frames: List<ByteArray>): BooleanArray {
        val out = ArrayList<Boolean>()
        for (i in 0 until PREAMBLE_PULSES) {
            out.add(true)
            out.add(false)
        }
        for (wire in frames) {
            for (i in 0 until MARKER_SLOTS) out.add(true)
            // NRZI + stuffing; the level before the first data slot is ON (the marker)
            var level = true
            var zeros = 0
            fun bit(b: Int) {
                if (b == 1) {
                    level = !level
                    zeros = 0
                } else {
                    zeros++
                }
                out.add(level)
                if (zeros == MAX_RUN - 1) { // stuff a 1 after three 0s
                    level = !level
                    zeros = 0
                    out.add(level)
                }
            }
            bit(1) // start bit: torch goes OFF right after the marker
            for (byte in wire) for (i in 7 downTo 0) bit((byte.toInt() shr i) and 1)
            if (level) bit(1) // end OFF so the next marker has a rising edge
        }
        return out.toBooleanArray()
    }

    /** Approximate number of slots for [frameCount] frames with [totalWireBytes] wire bytes in total. */
    fun estimateSlots(totalWireBytes: Int, frameCount: Int): Int =
        PREAMBLE_PULSES * 2 + frameCount * (MARKER_SLOTS + 2) + (totalWireBytes * 8 * 1.07).toInt()

    fun halfSymbolCount(totalWireBytes: Int): Int = estimateSlots(totalWireBytes, 1)
}
