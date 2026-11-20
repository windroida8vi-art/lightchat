import com.lightchat.core.*
import java.util.Random

var fails = 0
fun ck(name: String, c: Boolean) { if (!c) { fails++; println("FAIL: $name") } }

fun main() {
    val rnd = Random(7)

    // ---- TextCodec ----
    val samples = listOf(
        "Hello, world! 123", "سلام، من مهران هستم؟ حالتون چطوره؟", "کمک! مجروح داریم «لطفاً» ۱۲۳۴۵",
        "mixed سلام ok 😀 emoji ✓ و ی ك ي", "می‌خواهم", "", "\n\t x"
    )
    for (s in samples) {
        val b = TextCodec.encode(s)
        val d = TextCodec.decodePrefix(b)
        ck("codec '$s'", d.text == s && d.consumed == b.size)
        // every prefix must decode to a prefix of the text and never throw
        for (n in 0..b.size) {
            val p = TextCodec.decodePrefix(b, n)
            ck("prefix $n of '$s'", s.startsWith(p.text) && p.consumed <= n)
        }
    }
    val fa = "سلام من خوبم و شما چطورید؟"
    println("persian: utf8=${fa.toByteArray().size} compact=${TextCodec.encode(fa).size} bytes")
    ck("compact smaller", TextCodec.encode(fa).size < fa.toByteArray().size)

    // ---- Crypt ----
    val key = Crypt.deriveKey("pass 1")
    val key2 = Crypt.deriveKey("pass 2")
    val plain = ByteArray(100); rnd.nextBytes(plain)
    val enc = Crypt.apply(key, 0x1234, 0, plain)
    ck("crypt differs", !enc.contentEquals(plain))
    ck("crypt roundtrip", Crypt.apply(key, 0x1234, 0, enc).contentEquals(plain))
    ck("wrong key", !Crypt.apply(key2, 0x1234, 0, enc).contentEquals(plain))
    ck("wrong nonce", !Crypt.apply(key, 0x1235, 0, enc).contentEquals(plain))
    val part = Crypt.apply(key, 0x1234, 37, enc.copyOfRange(37, 70))
    ck("crypt random access", part.contentEquals(plain.copyOfRange(37, 70)))

    val kp = Crypt.deriveKey("رمز Abc ۱۲۳ !@#")
    ck("persian passphrase deterministic", kp.contentEquals(Crypt.deriveKey("رمز Abc ۱۲۳ !@#")))
    ck("passphrases differ", !kp.contentEquals(Crypt.deriveKey("رمز Abc ۱۲۴ !@#")) && !kp.contentEquals(Crypt.deriveKey("abc")))
    ck("persian passphrase roundtrip", Crypt.apply(kp, 9, 0, Crypt.apply(kp, 9, 0, plain)).contentEquals(plain))

    // ---- chunks / ACK ----
    for (len in 1..120) for (cs in intArrayOf(4, 8, 14)) {
        if (Msg.chunkCount(len, cs) > Msg.MAX_CHUNKS) continue
        val stream = ByteArray(len); rnd.nextBytes(stream)
        val frames = Msg.buildChunks(2, stream, false, cs)
        val back = java.io.ByteArrayOutputStream()
        for ((i, f) in frames.withIndex()) {
            val w = FrameCodec.parse(FrameCodec.encode(f))!!
            val c = Msg.parseChunk(w)!!
            ck("chunk meta", c.msgId == 2 && c.index == i && c.last == (i == frames.size - 1))
            back.write(c.data)
        }
        ck("chunk reassembly len=$len cs=$cs", back.toByteArray().contentEquals(stream))
    }
    val a = Msg.parseAck(FrameCodec.parse(FrameCodec.encode(Msg.buildAck(3, 4, 0xA5C3, 9, 5)))!!)!!
    ck("ack", a.msgId == 3 && a.maxSpeed == 4 && a.mask == 0xA5C3 && a.target == 9 && a.acker == 5)

    // ---- Assembler: out of order, duplicates, live text, new message with same id ----
    fun deliver(asm: Assembler, frames: List<Frame>, order: List<Int>, now: Long): Assembler.Result? {
        var r: Assembler.Result? = null
        for (i in order) r = asm.onChunk(Msg.parseChunk(frames[i])!!, now)
        return r
    }
    val text = "سلام! کمک لازم داریم. ما در ساختمان شماره ۵ هستیم."
    val stream = byteArrayOf(Msg.streamHeader(3, Msg.ALL).toByte()) + TextCodec.encode(text)
    val frames = Msg.buildChunks(1, stream, false, 8)
    val asm = Assembler()
    var prevLen = 0
    for (i in frames.indices) { // in order: live text only grows
        val rr = asm.onChunk(Msg.parseChunk(frames[i])!!, 1000L + i)
        val t = rr.message.text(null)
        ck("live grows", t.length >= prevLen && text.startsWith(t))
        prevLen = t.length
    }
    val fin = asm.current!!
    ck("complete", fin.complete && fin.text(null) == text && fin.mask == Msg.fullMask(frames.size))
    val dup = asm.onChunk(Msg.parseChunk(frames[1])!!, 2000L)
    ck("duplicate not new", !dup.isNewChunk && !dup.isNewMessage)
    // out of order with a gap: text shows only the contiguous part
    val asm2 = Assembler()
    deliver(asm2, frames, listOf(0, 2, 3), 5L)
    val gap = asm2.current!!.text(null)
    ck("gap text is prefix", text.startsWith(gap) && gap.length < text.length && !asm2.current!!.complete)
    ck("gap mask", asm2.current!!.mask == 0b1101)
    deliver(asm2, frames, listOf(1) + (4 until frames.size), 6L)
    ck("gap filled", asm2.current!!.complete && asm2.current!!.text(null) == text)
    // new message with the same msgId but different content must not be swallowed
    val other = Msg.buildChunks(1, byteArrayOf(Msg.streamHeader(3, Msg.ALL).toByte()) + TextCodec.encode("پیام دوم"), false, 8)
    var r2 = asm.onChunk(Msg.parseChunk(other[0])!!, 3000L)
    val firstWasNew = r2.isNewMessage
    for (i in 1 until other.size) r2 = asm.onChunk(Msg.parseChunk(other[i])!!, 3001L + i)
    ck("same id, new content = new message", firstWasNew && r2.message.complete && r2.message.text(null) == "پیام دوم")

    // encrypted message, live decrypt
    val nonce = 0xBEEF
    val secret = "مختصات: 38.08, 46.29"
    val cipher = Crypt.apply(key, nonce, 0, TextCodec.encode(secret))
    val encStream = byteArrayOf(Msg.streamHeader(2, Msg.ALL).toByte(), (nonce ushr 8).toByte(), nonce.toByte()) + cipher
    val ef = Msg.buildChunks(0, encStream, true, 8)
    val asm3 = Assembler()
    for (f in ef) {
        val lr = asm3.onChunk(Msg.parseChunk(f)!!, 1L)
        ck("enc live prefix", secret.startsWith(lr.message.text(key)))
    }
    val em = asm3.current!!
    ck("enc complete", em.text(key) == secret)
    ck("enc without key", em.text(null) == "🔒")
    ck("enc wrong key", em.text(key2) != secret)

    println(if (fails == 0) "MSG TESTS PASSED" else "$fails FAILURE(S)")
    if (fails != 0) System.exit(1)
}
