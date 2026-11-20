import com.lightchat.core.Crc16
import com.lightchat.core.Frame
import com.lightchat.core.FrameCodec
import com.lightchat.core.LightDecoder
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** Simulated torch + camera. Run with tools/run-sim.sh */
class Cfg(
    val halfMs: Double,
    val fps: Double,
    val expMs: Double,
    val jitterMs: Double,
    val noise: Double,
    val lo: Double = 20.0,
    val hi: Double = 220.0,
    val preNoisePulses: Int = 0
)

class Rx(val f: Frame, val t: Double)

var lastDec: LightDecoder? = null
const val T0 = 3000.0
const val TORCH_LATENCY = 25.0

/** Each transmission = list of frames; gapSlots idle half-bits between transmissions. */
fun simulate(txs: List<List<Frame>>, gapSlots: Int, cfg: Cfg, rnd: Random): List<Rx> {
    val seq = ArrayList<Boolean>()
    for ((i, tx) in txs.withIndex()) {
        for (x in FrameCodec.toSlots(tx.map { FrameCodec.encode(it) })) seq.add(x)
        if (i < txs.size - 1) for (k in 0 until gapSlots) seq.add(false)
    }
    for (k in 0 until 40) seq.add(false)

    val got = ArrayList<Rx>()
    var curT = 0.0
    val dec = LightDecoder { got.add(Rx(it, curT)) }
    lastDec = dec

    val starts = ArrayList<Double>()
    val ends = ArrayList<Double>()
    var tn = T0 - 300.0
    val pre = ArrayList<DoubleArray>()
    for (k in 0 until cfg.preNoisePulses) {
        val off = 60.0 + rnd.nextDouble() * 150.0
        val onD = 60.0 + rnd.nextDouble() * 150.0
        pre.add(doubleArrayOf(tn - off - onD, tn - off))
        tn -= (off + onD)
    }
    for (p in pre.reversed()) { starts.add(p[0]); ends.add(p[1]) }

    var cur = false
    var curStart = 0.0
    for (i in seq.indices) {
        val state = seq[i]
        if (state != cur) {
            val t = T0 + i * cfg.halfMs + TORCH_LATENCY + (rnd.nextDouble() * 2 - 1) * cfg.jitterMs
            if (state) curStart = t else { starts.add(curStart); ends.add(t) }
            cur = state
        }
    }
    val endT = T0 + seq.size * cfg.halfMs + TORCH_LATENCY
    if (cur) { starts.add(curStart); ends.add(endT) }

    val dt = 1000.0 / cfg.fps
    var t = rnd.nextDouble() * dt
    var si = 0
    while (t < endT + 2000.0) {
        val ts = t + (rnd.nextDouble() * 2 - 1) * 1.5
        val a = ts
        val b = ts + cfg.expMs
        while (si < starts.size && ends[si] < a - 1000) si++
        var onTime = 0.0
        for (n in si until starts.size) {
            if (starts[n] > b) break
            val s = max(a, starts[n])
            val e = min(b, ends[n])
            if (e > s) onTime += e - s
        }
        val frac = onTime / cfg.expMs
        val v = Math.round(cfg.lo + (cfg.hi - cfg.lo) * frac + rnd.nextGaussian() * cfg.noise).toDouble()
        curT = ts
        dec.feed(ts, v.coerceIn(0.0, 255.0).toFloat())
        t += dt
    }
    return got
}

fun same(a: Frame, b: Frame) = a.type == b.type && a.payload.contentEquals(b.payload)

fun randomFrame(rnd: Random, len: Int, type: Int = 0): Frame {
    val p = ByteArray(len)
    rnd.nextBytes(p)
    return Frame(type, p)
}

var failures = 0
fun check(name: String, cond: Boolean) {
    if (!cond) { failures++; println("FAIL: $name") }
}

fun main() {
    // ---- CRC / codec unit checks ----
    check("crc check value", Crc16.compute("123456789".toByteArray()) == 0x29B1)
    val rnd0 = Random(1)
    for (type in 0..2) for (len in 0..FrameCodec.MAX_PAYLOAD) {
        val f = randomFrame(rnd0, len, type)
        val w = FrameCodec.encode(f)
        val p = FrameCodec.parse(w)
        check("codec roundtrip type=$type len=$len", p != null && same(f, p))
        check("slot estimate", kotlin.math.abs(FrameCodec.toSlots(listOf(w)).size - FrameCodec.halfSymbolCount(w.size)) <= 6)
        for (bit in 0 until w.size * 8) {
            val c = w.copyOf()
            c[bit / 8] = (c[bit / 8].toInt() xor (1 shl (bit % 8))).toByte()
            check("single bit flip accepted type=$type len=$len bit=$bit", FrameCodec.parse(c) == null)
        }
    }
    for (b in 0 until 8) {
        val h = FrameCodec.encode(Frame(0, ByteArray(5)))[0].toInt() and 0xFF
        check("header single-bit flip detected (bit $b)", !FrameCodec.headerOk(h xor (1 shl b)))
    }

    // ---- end-to-end through the simulated camera ----
    val rnd = Random(2024)
    val trials = 30
    println(String.format("%-5s %-4s %-4s %-4s %-5s %-4s | %-9s late(ms) support", "half", "fps", "exp", "jit", "noise", "pre", "ok/trials"))
    val configs = ArrayList<Cfg>()
    for (h in FrameCodec.HALF_MS_CHOICES) for (fps in doubleArrayOf(30.0, 24.0, 60.0)) for (exp in doubleArrayOf(8.0, 20.0))
        configs.add(Cfg(h, fps, min(exp, 1000.0 / fps), 12.0, 4.0))
    for (h in FrameCodec.HALF_MS_CHOICES) {
        if (h >= 130.0) configs.add(Cfg(h, 60.0, 10.0, 30.0, 8.0)) // +-30 ms torch jitter only makes sense for slow speeds
        configs.add(Cfg(h, 30.0, 20.0, 12.0, 4.0, preNoisePulses = 3))
    }
    var worstNormal = 1.0
    var worstHarsh = 1.0
    var worstLate = 0.0
    for (cfg in configs) {
        var ok = 0
        var late = 0.0
        for (t in 0 until trials) {
            val nFrames = 1 + t % 4
            val frames = ArrayList<Frame>()
            for (i in 0 until nFrames) frames.add(randomFrame(rnd, intArrayOf(0, 3, 9, 15)[(t + i) % 4], i % 2))
            val got = simulate(listOf(frames), 0, cfg, rnd)
            val good = got.size == frames.size && frames.indices.all { same(got[it].f, frames[it]) }
            if (good) {
                ok++
                for (i in frames.indices) {
                    val endSlot = FrameCodec.toSlots(frames.subList(0, i + 1).map { FrameCodec.encode(it) }).size
                    val trueEnd = T0 + endSlot * cfg.halfMs + TORCH_LATENCY
                    late = max(late, got[i].t - trueEnd)
                }
            }
        }
        val rate = ok.toDouble() / trials
        // a speed is "supported" when a half-bit is at least 3.3 camera frames long (same rule as the app)
        val supported = FrameCodec.speedSupported(cfg.halfMs, cfg.fps)
        println(String.format("%-5.0f %-4.0f %-4.0f %-4.0f %-5.0f %-4d | %2d/%-6d %-7.0f %s", cfg.halfMs, cfg.fps, cfg.expMs, cfg.jitterMs, cfg.noise, cfg.preNoisePulses, ok, trials, late, if (supported) "yes" else "-"))
        if (supported) {
            if (cfg.jitterMs >= 30.0) worstHarsh = min(worstHarsh, rate) else worstNormal = min(worstNormal, rate)
            worstLate = max(worstLate, late / cfg.halfMs)
        }
    }
    check("supported normal configs reliable (worst=$worstNormal)", worstNormal >= 0.96)
    check("supported harsh-jitter configs mostly work (worst=$worstHarsh)", worstHarsh >= 0.85)
    check("live latency: frame delivered within 4 half-bits of its last bit (worst=${"%.1f".format(worstLate)})", worstLate <= 4.0)

    // ---- back-to-back transmissions through ONE decoder: data, then ack after a short gap ----
    for (h in doubleArrayOf(130.0, 100.0)) {
        var okAll = 0
        for (t in 0 until 20) {
            val d = listOf(randomFrame(rnd, 10, 0), randomFrame(rnd, 8, 0))
            val a = listOf(randomFrame(rnd, 3, 1))
            val got = simulate(listOf(d, a, d), (600.0 / h).toInt() + 1, Cfg(h, 30.0, 16.0, 10.0, 3.0), rnd)
            val want = d + a + d
            if (got.size == want.size && want.indices.all { same(got[it].f, want[it]) }) okAll++
        }
        println("3 transmissions, gap 600ms, half=$h: $okAll/20")
        check("back-to-back transmissions half=$h ($okAll/20)", okAll >= 19)
    }

    // ---- no false frames from pure noise or random flicker ----
    var falseFrames = 0
    for (run in 0 until 300) {
        val dec = LightDecoder { falseFrames++ }
        var t = 0.0
        var on = false
        var nextToggle = rnd.nextDouble() * 400
        val flicker = run % 2 == 0
        while (t < 120000.0) {
            if (flicker && t >= nextToggle) { on = !on; nextToggle = t + 40 + rnd.nextDouble() * 400 }
            val base = if (flicker) (if (on) 200.0 else 30.0) else 90.0
            dec.feed(t, (base + rnd.nextGaussian() * (if (flicker) 6.0 else 25.0)).coerceIn(0.0, 255.0).toFloat())
            t += 33.3
        }
    }
    check("no false frames (got $falseFrames)", falseFrames == 0)

    println(if (failures == 0) "ALL TESTS PASSED" else "$failures FAILURE(S)")
    if (failures != 0) System.exit(1)
}
