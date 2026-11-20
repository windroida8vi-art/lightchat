import com.lightchat.core.*
import java.util.Random

/** Full exchange on the simulated channel: chunks -> assembler -> ACK mask -> selective repeat. */
fun main() {
    val rnd = Random(99)
    val texts = listOf(
        "SOS کمک! نیاز به کمک فوری داریم",
        "من سالم و امن هستم",
        "مجروح داریم، آمبولانس لازم است. ما در ساختمان شماره ۵ طبقه‌ی دوم هستیم.",
        "Hello, this is a test message with some more words in it, 1234567890.",
        "آب و غذا نیاز داریم"
    )
    val cfgs = listOf(
        Cfg(130.0, 30.0, 16.0, 12.0, 4.0),
        Cfg(100.0, 30.0, 16.0, 25.0, 10.0), // harsh: +-25 ms torch jitter, strong noise
        Cfg(75.0, 60.0, 10.0, 12.0, 4.0)
    )
    var bad = 0
    for (cfg in cfgs) {
        var ok = 0
        var rounds = 0
        var worst = 0
        val n = 30
        for (trial in 0 until n) {
            val text = texts[trial % texts.size]
            val frames = Msg.buildChunks(trial and 3, byteArrayOf(Msg.streamHeader(1, Msg.ALL).toByte()) + TextCodec.encode(text), false, 8)
            val full = Msg.fullMask(frames.size)
            var missing = full
            val asm = Assembler()
            var r = 0
            while (r < 6 && missing != 0) {
                r++
                val send = frames.filterIndexed { i, _ -> (missing and (1 shl i)) != 0 }
                for (g in simulate(listOf(send), 0, cfg, rnd)) Msg.parseChunk(g.f)?.let { asm.onChunk(it, 1L) }
                val cur = asm.current ?: continue
                val ack = Msg.buildAck(cur.msgId, 3, cur.mask, 1, 2)
                val a = simulate(listOf(listOf(ack)), 0, cfg, rnd).firstNotNullOfOrNull { Msg.parseAck(it.f) }
                if (a != null) missing = missing and a.mask.inv() and full
            }
            if (missing == 0 && asm.current!!.text(null) == text) ok++
            rounds += r
            if (r > worst) worst = r
        }
        println(String.format("E2E half=%.0f fps=%.0f jit=%.0f noise=%.0f: delivered %d/%d, avg rounds %.2f, worst %d",
            cfg.halfMs, cfg.fps, cfg.jitterMs, cfg.noise, ok, n, rounds.toDouble() / n, worst))
        if (ok < n) bad++
    }
    println(if (bad == 0) "E2E PASSED" else "E2E: $bad config(s) lost messages")
    if (bad != 0) System.exit(1)
}
