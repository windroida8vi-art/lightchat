package com.lightchat.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Online (live) decoder: turns camera brightness samples into [Frame]s and calls [onFrame] for every
 * CRC-valid frame AS SOON AS ITS LAST BIT HAS ARRIVED. Pure Kotlin, no Android classes; feed it from ONE
 * thread (the @Volatile properties may be read from any thread). Line format: see [FrameCodec].
 *
 * Stages:
 *  1. Adaptive threshold (running min/max) turns brightness into ON/OFF; every change is an "edge"
 *     with a sub-frame time estimate (linear interpolation between two frames).
 *  2. HUNT: find  preamble pulses + a 7-slot ON marker. A least-squares fit of all preamble edges gives
 *     the slot time (snapped to the allowed speeds) and the clock phase for rising and falling edges.
 *  3. DATA: each later edge is put on the slot grid; slot levels become NRZI bits (stuffing removed),
 *     bytes, one frame. Slots whose edge would already have been seen by now keep their level, so the
 *     last bits need no extra edge.
 *  4. SEEK: after a frame (or after damage) wait for the next marker. A marker re-synchronises the clock
 *     and starts the next frame, so damage to one frame never spreads to the following ones.
 *  5. No edge for [IDLE_SLOTS] slots = end of the transmission.
 */
class LightDecoder(private val onFrame: (Frame) -> Unit) {

    companion object {
        const val MIN_CONTRAST = 35f
        private const val ENVELOPE_DECAY = 0.01f
        private const val MAX_HUNT_EDGES = 64
        private const val HUNT_IDLE_MS = 2500.0
        private const val IDLE_SLOTS = 9.0
        private const val MARKER_MIN = 5.5 // data runs are <= 4 slots (+ jitter), a marker is 7
        private const val MARKER_MAX = 10.0
        private const val SNAP_TOLERANCE = 0.12
    }

    private enum class State { HUNT, DATA, SEEK }

    // ---- brightness -> edges ----
    private var initialized = false
    private var hi = 0f
    private var lo = 0f
    private var on = false
    private var lastT = 0.0
    private var lastV = 0f
    private var frameMs = 33.0
    private var lastRiseT = 0.0
    private var lastEdgeT = 0.0

    // ---- HUNT ----
    private var state = State.HUNT
    private val hunt = ArrayList<Double>()

    // ---- DATA / SEEK (slot 0 = first slot of the marker that started the current frame) ----
    private var h = 0.0
    private var phiR = 0.0
    private var phiF = 0.0
    private var lastEdgeSlot = 0
    private var curLevel = false
    private var known = 0
    private var prevLvl = true
    private var started = false
    private var zeros = 0
    private var pState = 1 // 1 = expect header, 2 = body
    private var bitCount = 0
    private var curByte = 0
    private val wire = ByteArray(1 + FrameCodec.MAX_PAYLOAD + 2)
    private var wn = 0
    private var need = 0

    // ---- public status ----
    @Volatile var signalOn: Boolean = false
        private set

    /** max-min brightness range; below [MIN_CONTRAST] nothing is detected. */
    @Volatile var contrast: Float = 0f
        private set

    /** true while a transmission is being decoded. */
    @Volatile var receiving: Boolean = false
        private set

    /** measured camera frame rate. */
    @Volatile var fps: Double = 30.0
        private set

    /** Average edge timing error as a fraction of a slot (0 = perfect, > 0.3 = bad). */
    @Volatile var jitter: Double = 0.0
        private set

    @Volatile var goodFrames: Int = 0
        private set

    /** Frames lost to damage (bad CRC, bad header, cut short). */
    @Volatile var badFrames: Int = 0
        private set

    /** Transmissions locked onto / markers used to re-synchronise inside a transmission. */
    @Volatile var sessions: Int = 0
        private set

    @Volatile var resyncs: Int = 0
        private set

    /** Slot time (ms) of the transmission being decoded or last decoded. */
    @Volatile var lastHalfMs: Double = 0.0
        private set

    /** Called (on the feeding thread) when a transmission has ended, after all its frames were delivered. */
    @Volatile
    var onSessionEnd: (() -> Unit)? = null

    fun diagnostics(): String =
        "فریم‌ریت: ${"%.1f".format(fps)}  •  کنتراست: ${contrast.toInt()}\n" +
            "ارسال‌های شناخته‌شده: $sessions  •  همگام‌سازی مجدد: $resyncs\n" +
            "تکه‌ی سالم: $goodFrames  •  تکه‌ی خراب: $badFrames\n" +
            "لرزش زمانی لبه‌ها: ${"%.0f".format(jitter * 100)}% از یک اسلات  •  اسلات آخر: ${lastHalfMs.toInt()}ms"

    fun reset() {
        initialized = false
        on = false
        signalOn = false
        contrast = 0f
        endSession(false)
    }

    /** @param tMs frame time in milliseconds (any origin), @param v brightness 0..255 */
    fun feed(tMs: Double, v: Float) {
        if (!initialized) {
            initialized = true
            hi = v
            lo = v
            lastT = tMs
            lastV = v
            on = false
            return
        }

        val dt = tMs - lastT
        if (dt > 4.0 && dt < 250.0) {
            frameMs += (dt - frameMs) * 0.05
            fps = 1000.0 / frameMs
        }

        if (v > hi) hi = v else hi += (v - hi) * ENVELOPE_DECAY
        if (v < lo) lo = v else lo += (v - lo) * ENVELOPE_DECAY
        val range = hi - lo
        contrast = range
        val mid = (hi + lo) / 2f

        var newOn = on
        if (range >= MIN_CONTRAST) {
            val margin = range * 0.1f
            if (!on && v > mid + margin) newOn = true
            else if (on && v < mid - margin) newOn = false
        } else {
            newOn = false
        }

        if (newOn != on) {
            val dv = v - lastV
            val te: Double = if (abs(dv) > 1e-3f) {
                val frac = ((mid - lastV) / dv).toDouble().coerceIn(0.0, 1.0)
                lastT + frac * (tMs - lastT)
            } else {
                (lastT + tMs) / 2.0
            }
            on = newOn
            onEdge(te, newOn)
        }

        lastT = tMs
        lastV = v
        signalOn = on

        when (state) {
            State.DATA -> {
                // Slots whose boundary edge would already have been seen by now keep their level.
                val phi = (phiR + phiF) / 2.0
                val kn = floor((tMs - 2.6 * frameMs - phi) / h - 0.5).toInt()
                if (kn > known) fillTo(kn, curLevel)
                if (state == State.SEEK && tMs - lastEdgeT > IDLE_SLOTS * h) endSession(true)
            }
            State.SEEK -> if (tMs - lastEdgeT > IDLE_SLOTS * h) endSession(true)
            State.HUNT -> if (hunt.isNotEmpty() && tMs - hunt[hunt.size - 1] > HUNT_IDLE_MS) hunt.clear()
        }
    }

    // =========================================================================================

    private fun onEdge(te: Double, rising: Boolean) {
        if (rising) lastRiseT = te
        lastEdgeT = te
        when (state) {
            State.HUNT -> huntEdge(te, rising)
            State.DATA -> dataEdge(te, rising)
            State.SEEK -> seekEdge(te, rising)
        }
    }

    private fun huntEdge(te: Double, rising: Boolean) {
        if (hunt.isEmpty() && !rising) return
        hunt.add(te)
        if (hunt.size > MAX_HUNT_EDGES) hunt.removeAt(0)
        if (!rising && hunt.size >= 8) tryLock()
    }

    private fun tryLock() {
        val k = hunt.size - 1 // last edge = falling edge that ends the marker
        // noise before the real preamble may add edges: try the longest fit first, then shorter ones
        var q = min(FrameCodec.PREAMBLE_PULSES, (k - 1) / 2)
        while (q >= 3) {
            if (lockWith(k, q)) return
            q--
        }
    }

    private fun lockWith(k: Int, q: Int): Boolean {
        val m = FrameCodec.MARKER_SLOTS
        val rx = ArrayList<Double>(); val ry = ArrayList<Double>()
        val fx = ArrayList<Double>(); val fy = ArrayList<Double>()
        for (i in 0..q) { rx.add(-2.0 * i); ry.add(hunt[k - 1 - 2 * i]) }
        for (i in 1..q) { fx.add(-2.0 * i + 1); fy.add(hunt[k - 2 * i]) }
        fx.add(m.toDouble()); fy.add(hunt[k])
        val est = slope(rx, ry, fx, fy)
        if (est <= 0.0) return false

        var hh = 0.0
        var bestErr = Double.MAX_VALUE
        for (c in FrameCodec.HALF_MS_CHOICES) {
            val err = abs(est - c) / c
            if (err < bestErr) {
                bestErr = err
                hh = c
            }
        }
        if (bestErr > SNAP_TOLERANCE) return false
        val markerSlots = (hunt[k] - hunt[k - 1]) / hh
        if (markerSlots < MARKER_MIN || markerSlots > MARKER_MAX) return false

        val rs = ArrayList<Double>()
        for (i in 0..q) rs.add(hunt[k - 1 - 2 * i] + 2.0 * i * hh)
        val fs = ArrayList<Double>()
        fs.add(hunt[k] - m * hh)
        for (i in 1..q) fs.add(hunt[k - 2 * i] + (2.0 * i - 1) * hh)
        val pr = median(ArrayList(rs))
        val pf = median(ArrayList(fs))
        // every preamble edge must sit on the grid (rejects noise edges in front of the preamble)
        for (v in rs) if (abs(v - pr) > 0.42 * hh) return false
        for (v in fs) if (abs(v - pf) > 0.42 * hh) return false

        h = hh
        phiR = pr
        phiF = pf
        hunt.clear()
        sessions++
        lastHalfMs = hh
        jitter = 0.0
        startFrame()
        receiving = true
        return true
    }

    /** Called when a marker has just ended (its falling edge was seen): the frame's data slots start. */
    private fun startFrame() {
        state = State.DATA
        lastEdgeSlot = FrameCodec.MARKER_SLOTS
        curLevel = false
        known = FrameCodec.MARKER_SLOTS
        prevLvl = true
        started = false
        zeros = 0
        pState = 1
        bitCount = 0
        curByte = 0
        wn = 0
    }

    private fun dataEdge(te: Double, rising: Boolean) {
        val phi = if (rising) phiR else phiF
        val m = Math.round((te - phi) / h).toInt()
        if (m <= lastEdgeSlot) { // two edges on one slot: the grid is lost
            abortFrame()
            seekEdge(te, rising)
            return
        }
        val res = te - (phi + m * h)
        jitter += (abs(res) / h - jitter) * 0.05
        if (rising) phiR += 0.15 * res else phiF += 0.15 * res

        fillTo(m, curLevel)
        if (state != State.DATA) { // the gap was an illegal run: damaged frame, maybe the next marker starts here
            seekEdge(te, rising)
            return
        }
        lastEdgeSlot = m
        curLevel = rising
    }

    private fun seekEdge(te: Double, rising: Boolean) {
        if (rising) return // lastRiseT already holds it
        val run = (te - lastRiseT) / h
        if (run < MARKER_MIN || run > MARKER_MAX) return
        // A marker. Re-synchronise: blend with the old phase when it is consistent, else trust the new edges.
        val newR = lastRiseT
        val newF = te - FrameCodec.MARKER_SLOTS * h
        phiR = blend(phiR, newR)
        phiF = blend(phiF, newF)
        resyncs++
        startFrame()
    }

    private fun blend(old: Double, fresh: Double): Double {
        val n = Math.round((fresh - old) / h)
        val aligned = old + n * h
        return if (abs(fresh - aligned) < 0.3 * h) 0.5 * (fresh + aligned) else fresh
    }

    /** Declare all slots below [slotExclusive] to have level [level] and decode what is possible. */
    private fun fillTo(slotExclusive: Int, level: Boolean) {
        while (state == State.DATA && known < slotExclusive) {
            known++
            onLevel(level)
        }
    }

    private fun onLevel(level: Boolean) {
        val bit = if (level != prevLvl) 1 else 0
        prevLvl = level
        if (!started) {
            if (bit != 1) abortFrame() else {
                started = true
                zeros = 0
            }
            return
        }
        if (zeros == FrameCodec.MAX_RUN - 1) { // a stuffed 1 must follow
            if (bit == 1) zeros = 0 else abortFrame()
            return
        }
        if (bit == 1) zeros = 0 else zeros++
        onBit(bit)
    }

    private fun onBit(bit: Int) {
        curByte = (curByte shl 1) or bit
        bitCount++
        if (bitCount < 8) return
        val b = curByte
        bitCount = 0
        curByte = 0
        if (pState == 1) {
            if (!FrameCodec.headerOk(b)) {
                abortFrame()
                return
            }
            wire[0] = b.toByte()
            wn = 1
            need = 1 + FrameCodec.headerLen(b) + 2
            pState = 2
        } else {
            wire[wn] = b.toByte()
            wn++
            if (wn == need) {
                val f = FrameCodec.parse(wire.copyOf(wn))
                state = State.SEEK
                if (f != null) {
                    goodFrames++
                    onFrame(f)
                } else {
                    badFrames++
                }
            }
        }
    }

    /** The frame being decoded is damaged: drop it and wait for the next marker. */
    private fun abortFrame() {
        if (state == State.DATA) {
            badFrames++
            state = State.SEEK
        }
    }

    private fun endSession(notify: Boolean) {
        val was = state != State.HUNT
        state = State.HUNT
        hunt.clear()
        receiving = false
        if (was && notify) onSessionEnd?.invoke()
    }

    // =========================================================================================

    /** Common slope of two point groups that each have their own intercept. */
    private fun slope(x1: List<Double>, y1: List<Double>, x2: List<Double>, y2: List<Double>): Double {
        var sxy = 0.0
        var sxx = 0.0
        for ((xs, ys) in listOf(Pair(x1, y1), Pair(x2, y2))) {
            val mx = xs.average()
            val my = ys.average()
            for (i in xs.indices) {
                sxy += (xs[i] - mx) * (ys[i] - my)
                sxx += (xs[i] - mx) * (xs[i] - mx)
            }
        }
        return if (sxx > 0.0) sxy / sxx else 0.0
    }

    private fun median(values: MutableList<Double>): Double {
        values.sort()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2.0
    }
}
