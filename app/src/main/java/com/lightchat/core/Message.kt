package com.lightchat.core

/**
 * Message layer on top of frames (group capable: up to 15 phones share one light channel).
 *
 * Stream of a message = [header byte][body]
 *   header = senderId(4 bits) << 4 | destId(4 bits)       destId 0 = everybody, 1..15 = one phone
 *   body   = compact text bytes, or [nonce 2][ciphertext] when encrypted
 * The stream is cut into chunks:  DATA payload = [meta][data bytes...]
 *   meta = msgId(2 bits) << 6 | last(1) << 5 | encrypted(1) << 4 | chunkIndex(4 bits)
 * ACK payload  = [msgId(2) << 6 | maxSpeedIndex(3) << 3][mask hi][mask lo][target(4) << 4 | acker(4)]
 *   target = the phone whose message is acknowledged, acker = the phone that sends this ACK
 * HELLO (CTRL) payload = [2][id][uid hi][uid lo][name bytes <= 11]   (tells the others who is who)
 *
 * The sender sends all chunks in ONE transmission. Every receiver shows chunks live as they arrive;
 * after the transmission ends each addressed receiver answers with an ACK in its own time slot (so ACKs
 * do not collide). The sender re-sends only chunks that somebody is still missing.
 */
object Msg {
    const val MAX_CHUNKS = 16
    const val DEFAULT_CHUNK = 8
    const val MAX_CHUNK = FrameCodec.MAX_PAYLOAD - 1
    const val ALL = 0
    const val MAX_NODE = 15
    const val CTRL_HELLO = 2
    const val MAX_NAME_BYTES = 11

    class Chunk(val msgId: Int, val last: Boolean, val encrypted: Boolean, val index: Int, val data: ByteArray)
    class Ack(val msgId: Int, val maxSpeed: Int, val mask: Int, val target: Int, val acker: Int)
    class Hello(val id: Int, val uid: Int, val name: String)

    fun streamHeader(sender: Int, dest: Int): Int = ((sender and 15) shl 4) or (dest and 15)

    fun maxStreamBytes(chunkSize: Int): Int = MAX_CHUNKS * chunkSize

    fun chunkCount(streamLen: Int, chunkSize: Int): Int = (streamLen + chunkSize - 1) / chunkSize

    /** Stream (header + body) -> DATA frames, one per chunk. */
    fun buildChunks(msgId: Int, stream: ByteArray, encrypted: Boolean, chunkSize: Int): List<Frame> {
        require(stream.isNotEmpty()) { "empty message" }
        require(chunkSize in 1..MAX_CHUNK) { "bad chunk size" }
        val n = chunkCount(stream.size, chunkSize)
        require(n <= MAX_CHUNKS) { "message too long" }
        val out = ArrayList<Frame>()
        for (i in 0 until n) {
            val from = i * chunkSize
            val to = minOf(stream.size, from + chunkSize)
            val p = ByteArray(1 + to - from)
            p[0] = (((msgId and 3) shl 6) or (if (i == n - 1) 0x20 else 0) or (if (encrypted) 0x10 else 0) or i).toByte()
            System.arraycopy(stream, from, p, 1, to - from)
            out.add(Frame(FrameCodec.TYPE_DATA, p))
        }
        return out
    }

    fun parseChunk(f: Frame): Chunk? {
        if (f.type != FrameCodec.TYPE_DATA || f.payload.size < 2) return null
        val m = f.payload[0].toInt() and 0xFF
        return Chunk((m ushr 6) and 3, (m and 0x20) != 0, (m and 0x10) != 0, m and 15, f.payload.copyOfRange(1, f.payload.size))
    }

    fun buildAck(msgId: Int, maxSpeed: Int, mask: Int, target: Int, acker: Int): Frame {
        val p = byteArrayOf(
            (((msgId and 3) shl 6) or ((maxSpeed and 7) shl 3)).toByte(),
            ((mask ushr 8) and 0xFF).toByte(),
            (mask and 0xFF).toByte(),
            (((target and 15) shl 4) or (acker and 15)).toByte()
        )
        return Frame(FrameCodec.TYPE_ACK, p)
    }

    fun parseAck(f: Frame): Ack? {
        if (f.type != FrameCodec.TYPE_ACK || f.payload.size != 4) return null
        val b0 = f.payload[0].toInt() and 0xFF
        val mask = ((f.payload[1].toInt() and 0xFF) shl 8) or (f.payload[2].toInt() and 0xFF)
        val ids = f.payload[3].toInt() and 0xFF
        return Ack((b0 ushr 6) and 3, (b0 ushr 3) and 7, mask, (ids ushr 4) and 15, ids and 15)
    }

    /** Name as bytes, shortened (whole symbols only) to fit a HELLO frame. */
    fun clampName(name: String): ByteArray {
        var s = name.trim()
        var b = TextCodec.encode(s)
        while (b.size > MAX_NAME_BYTES && s.isNotEmpty()) {
            s = s.substring(0, s.length - 1)
            b = TextCodec.encode(s)
        }
        return b
    }

    fun buildHello(id: Int, uid: Int, name: String): Frame {
        val nb = clampName(name)
        val p = ByteArray(4 + nb.size)
        p[0] = CTRL_HELLO.toByte()
        p[1] = (id and 15).toByte()
        p[2] = ((uid ushr 8) and 0xFF).toByte()
        p[3] = (uid and 0xFF).toByte()
        System.arraycopy(nb, 0, p, 4, nb.size)
        return Frame(FrameCodec.TYPE_CTRL, p)
    }

    fun parseHello(f: Frame): Hello? {
        if (f.type != FrameCodec.TYPE_CTRL || f.payload.size < 4 || f.payload[0].toInt() != CTRL_HELLO) return null
        val id = f.payload[1].toInt() and 15
        val uid = ((f.payload[2].toInt() and 0xFF) shl 8) or (f.payload[3].toInt() and 0xFF)
        val name = TextCodec.decodePrefix(f.payload.copyOfRange(4, f.payload.size)).text
        return Hello(id, uid, name)
    }

    fun fullMask(chunks: Int): Int = if (chunks >= 16) 0xFFFF else (1 shl chunks) - 1
}

/**
 * Sender side bookkeeping of ACKs. [expected] = phones that should answer (empty = audience unknown:
 * then one complete ACK from anybody is enough).
 */
class AckTracker(private val fullMask: Int, expected: Collection<Int>) {
    private val expectedSet = expected.toSet()
    private val masks = HashMap<Int, Int>()

    fun onAck(acker: Int, mask: Int) {
        masks[acker] = (masks[acker] ?: 0) or (mask and fullMask)
    }

    val anyAck: Boolean get() = masks.isNotEmpty()

    val allExpectedAnswered: Boolean get() = expectedSet.isNotEmpty() && expectedSet.all { masks.containsKey(it) }

    fun delivered(): Boolean =
        if (expectedSet.isEmpty()) masks.values.any { it == fullMask }
        else expectedSet.all { (masks[it] ?: 0) == fullMask }

    /** Chunks that at least one relevant receiver still lacks (everything if nobody answered yet). */
    fun missingMask(): Int {
        val relevant: Collection<Int> = if (expectedSet.isEmpty()) masks.keys else expectedSet
        if (relevant.isEmpty()) return fullMask
        var miss = 0
        for (id in relevant) miss = miss or (fullMask and (masks[id] ?: 0).inv())
        return miss
    }

    fun ackedBy(): Set<Int> = masks.filter { it.value == fullMask }.keys
}

/** Receiver side: collects chunks of many messages (many senders) and renders the text so far. */
class Assembler {
    class Message(val msgId: Int, val encrypted: Boolean) {
        val parts = arrayOfNulls<ByteArray>(Msg.MAX_CHUNKS)
        var lastIndex = -1
        var lastActivity = 0L
        var shownComplete = false
        var sender = -1 // known once chunk 0 (or the transmission's sender) is known
        var dest = Msg.ALL

        val mask: Int
            get() {
                var m = 0
                for (i in 0 until Msg.MAX_CHUNKS) if (parts[i] != null) m = m or (1 shl i)
                return m
            }

        val complete: Boolean
            get() = lastIndex >= 0 && (0..lastIndex).all { parts[it] != null }

        /** Bytes of the contiguous run of chunks starting at chunk 0 (header byte included). */
        fun contiguous(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < Msg.MAX_CHUNKS && parts[i] != null) {
                out.write(parts[i]!!, 0, parts[i]!!.size)
                i++
            }
            return out.toByteArray()
        }

        /** Body bytes (after the header byte) of everything received contiguously so far. */
        fun body(): ByteArray {
            val c = contiguous()
            return if (c.isEmpty()) c else c.copyOfRange(1, c.size)
        }

        /** Text of everything received contiguously so far. [key] = derived key or null. */
        fun text(key: ByteArray?): String {
            val b = body()
            if (!encrypted) return TextCodec.decodePrefix(b).text
            if (key == null) return "🔒"
            if (b.size < 2) return ""
            val nonce = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
            val plain = Crypt.apply(key, nonce, 0, b.copyOfRange(2, b.size))
            return TextCodec.decodePrefix(plain).text
        }
    }

    private val active = ArrayList<Message>()

    /** The message the most recent chunk belonged to. */
    var current: Message? = null
        private set

    class Result(val message: Message, val isNewChunk: Boolean, val isNewMessage: Boolean)

    private fun stale(m: Message, now: Long) = m.complete && now - m.lastActivity > 300_000L

    private fun sameContent(m: Message, c: Msg.Chunk): Boolean {
        val old = m.parts[c.index] ?: return true
        return old.contentEquals(c.data)
    }

    /**
     * @param sessionSender sender of the transmission this chunk arrived in, if known (the chunk 0 of that
     * transmission was seen), else -1. Needed to tell apart senders that use the same 2-bit message id.
     */
    fun onChunk(c: Msg.Chunk, now: Long, sessionSender: Int = -1): Result {
        val sender = if (c.index == 0 && c.data.isNotEmpty()) (c.data[0].toInt() ushr 4) and 15 else sessionSender

        var m: Message? = null
        if (sender >= 0) {
            m = active.lastOrNull { it.sender == sender && it.msgId == c.msgId && it.encrypted == c.encrypted && !stale(it, now) }
        }
        if (m == null && sender < 0) {
            // sender unknown: a duplicate of a chunk we already hold, or - if exactly one open message
            // could own it - a missing chunk of that message
            val same = active.filter { it.msgId == c.msgId && it.encrypted == c.encrypted && !stale(it, now) }
            val dup = same.filter { it.parts[c.index]?.contentEquals(c.data) == true }
            if (dup.size == 1) {
                m = dup[0]
            } else if (dup.isEmpty()) {
                val open = same.filter { !it.complete && it.parts[c.index] == null }
                if (open.size == 1) m = open[0]
            }
        }
        if (m != null && !sameContent(m, c)) m = null // same id, different content = a new message

        var newMessage = false
        if (m == null) {
            m = Message(c.msgId, c.encrypted)
            m.sender = sender
            active.add(m)
            if (active.size > 16) active.removeAt(0)
            newMessage = true
        }
        val fresh = m.parts[c.index] == null
        if (fresh) m.parts[c.index] = c.data
        if (c.last) m.lastIndex = c.index
        m.lastActivity = now
        if (c.index == 0 && c.data.isNotEmpty()) {
            m.sender = (c.data[0].toInt() ushr 4) and 15
            m.dest = c.data[0].toInt() and 15
            // adopt chunks that arrived earlier without a known sender
            val orphans = active.filter {
                it !== m && it.sender < 0 && it.msgId == m.msgId && it.encrypted == m.encrypted && now - it.lastActivity < 180_000L
            }
            for (o in orphans) {
                for (i in 0 until Msg.MAX_CHUNKS) if (m.parts[i] == null && o.parts[i] != null) m.parts[i] = o.parts[i]
                if (o.lastIndex >= 0 && m.lastIndex < 0) m.lastIndex = o.lastIndex
                active.remove(o)
            }
        }
        current = m
        return Result(m, fresh, newMessage)
    }
}
