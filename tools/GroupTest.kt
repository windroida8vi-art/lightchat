import com.lightchat.core.*
import java.util.Random

var gfails = 0
fun gk(name: String, c: Boolean) { if (!c) { gfails++; println("FAIL: $name") } }

fun stream(sender: Int, dest: Int, text: String) = byteArrayOf(Msg.streamHeader(sender, dest).toByte()) + TextCodec.encode(text)

fun main() {
    val rnd = Random(5)

    // ---- HELLO ----
    for (name in listOf("مهران", "Ali", "کاربر ۱۲", "یک نام خیلی خیلی بلند فارسی", "")) {
        val f = FrameCodec.parse(FrameCodec.encode(Msg.buildHello(7, 0xBEEF, name)))!!
        val h = Msg.parseHello(f)!!
        val expect = TextCodec.decodePrefix(Msg.clampName(name)).text
        gk("hello '$name'", h.id == 7 && h.uid == 0xBEEF && h.name == expect && Msg.clampName(name).size <= Msg.MAX_NAME_BYTES)
    }
    gk("hello not data", Msg.parseChunk(Msg.buildHello(1, 2, "x")) == null)

    // ---- two senders with the SAME msgId talking at the same time (alternating transmissions) ----
    run {
        val ta = "سلام از طرف علی، ما در ساختمان ۵ هستیم"
        val tb = "Bob here: water needed at the north gate please"
        val fa = Msg.buildChunks(2, stream(1, Msg.ALL, ta), false, 8)
        val fb = Msg.buildChunks(2, stream(2, Msg.ALL, tb), false, 8)
        val asm = Assembler()
        val n = maxOf(fa.size, fb.size)
        for (i in 0 until n) {
            if (i < fa.size) asm.onChunk(Msg.parseChunk(fa[i])!!, 100L + i, 1)
            if (i < fb.size) asm.onChunk(Msg.parseChunk(fb[i])!!, 200L + i, 2)
        }
        // find messages by walking: re-deliver duplicates to get the Message objects
        val ma = asm.onChunk(Msg.parseChunk(fa[0])!!, 300L, 1).message
        val mb = asm.onChunk(Msg.parseChunk(fb[0])!!, 301L, 2).message
        gk("two senders same msgId: A", ma.sender == 1 && ma.complete && ma.text(null) == ta)
        gk("two senders same msgId: B", mb.sender == 2 && mb.complete && mb.text(null) == tb)
        gk("distinct messages", ma !== mb)
    }

    // ---- chunk 0 lost, later chunks (with session sender known only after chunk 0 arrives) ----
    run {
        val t = "پیام برای تست گم شدن تکه‌ی اول"
        val f = Msg.buildChunks(1, stream(3, Msg.ALL, t), false, 6)
        val asm = Assembler()
        for (i in 1 until f.size) asm.onChunk(Msg.parseChunk(f[i])!!, 10L + i, -1) // orphan chunks
        gk("orphan has no text yet", asm.current!!.text(null) == "")
        val r = asm.onChunk(Msg.parseChunk(f[0])!!, 50L, 3) // retransmitted chunk 0 adopts the orphan
        gk("orphan adopted", r.message.sender == 3 && r.message.complete && r.message.text(null) == t)
    }

    // ---- private messages carry dest ----
    run {
        val f = Msg.buildChunks(0, stream(4, 9, "فقط برای تو"), false, 8)
        val m = Assembler().onChunk(Msg.parseChunk(f[0])!!, 1L, 4).message
        gk("dest parsed", m.sender == 4 && m.dest == 9)
    }

    // ---- AckTracker ----
    run {
        val full = Msg.fullMask(5)
        val t = AckTracker(full, listOf(2, 3))
        gk("tracker start", !t.delivered() && t.missingMask() == full && !t.allExpectedAnswered)
        t.onAck(2, 0b11111)
        gk("one of two done", !t.delivered() && t.missingMask() == full && !t.allExpectedAnswered)
        t.onAck(3, 0b10110)
        gk("both answered, 3 lacks chunks 0 and 3", t.allExpectedAnswered && !t.delivered() && t.missingMask() == 0b01001)
        t.onAck(3, 0b01001)
        gk("union completes", t.delivered() && t.missingMask() == 0 && t.ackedBy() == setOf(2, 3))
        val u = AckTracker(full, emptyList())
        gk("unknown audience: nothing yet", !u.delivered() && u.missingMask() == full)
        u.onAck(5, 0b00111)
        gk("unknown audience: partial", !u.delivered() && u.missingMask() == 0b11000 && u.anyAck)
        u.onAck(5, 0b11000)
        gk("unknown audience: complete", u.delivered())
    }

    // ---- group exchange over the simulated optical channel: A -> {B, C}, independent noise per listener ----
    for (cfg in listOf(Cfg(130.0, 30.0, 16.0, 12.0, 4.0), Cfg(100.0, 30.0, 16.0, 25.0, 10.0))) {
        var ok = 0
        var rounds = 0
        val n = 20
        for (trial in 0 until n) {
            val text = listOf("SOS کمک! نیاز به کمک فوری داریم", "ما سه نفر هستیم و همه سالم‌ایم، منتظر دستور می‌مانیم", "water and food needed at gate 4")[trial % 3]
            val frames = Msg.buildChunks(trial and 3, stream(1, Msg.ALL, text), false, 8)
            val full = Msg.fullMask(frames.size)
            val tracker = AckTracker(full, listOf(2, 3))
            val asm = mapOf(2 to Assembler(), 3 to Assembler())
            var r = 0
            while (r < 6 && !tracker.delivered()) {
                r++
                val send = frames.filterIndexed { i, _ -> (tracker.missingMask() and (1 shl i)) != 0 }
                for ((id, a) in asm) { // every listener hears the same light, with its own noise
                    var session = -1
                    for (g in simulate(listOf(send), 0, cfg, rnd)) {
                        val c = Msg.parseChunk(g.f) ?: continue
                        if (c.index == 0) session = 1
                        a.onChunk(c, 1L, session)
                    }
                    val cur = a.current ?: continue
                    // each listener answers in its own slot: the sender hears two ACK transmissions
                    val ack = Msg.buildAck(cur.msgId, 3, cur.mask, 1, id)
                    val heard = simulate(listOf(listOf(ack)), 0, cfg, rnd).firstNotNullOfOrNull { Msg.parseAck(it.f) }
                    if (heard != null && heard.target == 1) tracker.onAck(heard.acker, heard.mask)
                }
            }
            val allGot = asm.values.all { it.current != null && it.current!!.complete && it.current!!.text(null) == text }
            if (tracker.delivered() && allGot) ok++
            rounds += r
        }
        println(String.format("GROUP A->B,C half=%.0f jit=%.0f noise=%.0f: delivered %d/%d, avg rounds %.2f", cfg.halfMs, cfg.jitterMs, cfg.noise, ok, n, rounds.toDouble() / n))
        gk("group delivery half=${cfg.halfMs}", ok == n)
    }

    println(if (gfails == 0) "GROUP TESTS PASSED" else "$gfails FAILURE(S)")
    if (gfails != 0) System.exit(1)
}
