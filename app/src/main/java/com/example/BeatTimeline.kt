package com.example

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * STEP 1 — THE BEAT TIMELINE
 * ============================================================
 *
 * Works out WHERE THE BEATS ARE in a track, so the visualiser can READ them
 * instead of trying to detect them live.
 *
 * WHY THIS IS THE ADVANTAGE
 *   Every visualiser that uses a microphone is guessing. It hears the music
 *   through a room - distance, echo, the mic, background noise - so it has to
 *   filter noise out, and every filter that removes noise also removes real
 *   beats.
 *
 *   This reads the audio the app is ALREADY DECODING to play it. The exact
 *   samples. No room, no echo, no noise floor. Nothing to filter out.
 *
 * It is not cleverness. It is cleaner data.
 *
 * WHERE IT IS FED FROM
 *   MainActivity's decoder, in musicPlay(), at the line that writes decoded
 *   audio to the AudioTrack:
 *
 *       if (writeLength > 0) {
 *           timeline.feed(bytes, writeOffset, writeLength)   // <- this
 *           at.write(bytes, writeOffset, writeLength)
 *       }
 *
 *   Same buffer. No second decode. No extra I/O.
 *
 * COST
 *   One small FFT per hop. Reused buffers, no per-buffer allocation. Nothing
 *   runs per frame - this is analysis, not drawing.
 */
class BeatTimeline(private val sampleRate: Int, private val channels: Int = 1) {

    /** One beat: where it is in the stream, and how hard it hits. */
    data class Beat(val samplePosition: Long, val strength: Float, val colour: Float)

    // ------------------------------------------------------------
    // analysis geometry
    // ------------------------------------------------------------
    private val fftSize = 512
    private val hop = 256
    private val bins = fftSize / 2 + 1

    /** how much envelope history we keep, for the tempo estimate */
    private val envelopeCapacity = 2048

    // ------------------------------------------------------------
    // reusable buffers - nothing here is allocated per buffer
    // ------------------------------------------------------------
    private val window = FloatArray(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mag = FloatArray(bins)
    private val prevMag = FloatArray(bins)
    private val twCos = FloatArray(fftSize / 2)
    private val twSin = FloatArray(fftSize / 2)

    /** the onset envelope: one value per hop */
    private val envelope = FloatArray(envelopeCapacity)
    private var envelopeCount = 0
    private var envelopeWrite = 0

    /** samples waiting to fill one analysis frame */
    private val pending = FloatArray(fftSize * 4)
    private var pendingCount = 0

    /** how many samples have gone in so far, so every beat has a position */
    private var samplesFed = 0L

    /** the timeline, in order */
    private val beats = ArrayList<Beat>(4096)

    // ------------------------------------------------------------
    // tempo lock
    // ------------------------------------------------------------
    private var tempoLagHops = 0        // period of the beat, in hops
    private var phaseHops = 0           // which hop the beat lands on
    private var hopsSeen = 0
    private var lastBeatHop = -100000

    private var meanMag = 0f

    /** THE COLOUR. The spectral centroid of the frame that fired the beat -
     *  where the energy sits in the spectrum. A bright hit (hats, a snare,
     *  a high synth) sits high; a dark hit (a kick, a bass note) sits low.
     *  That is the music deciding the colour, not a hardcoded rotation. */
    private var lastCentroid = 0.5f

    init {
        // Hann window, and the twiddle factors for the FFT
        for (i in 0 until fftSize) {
            window[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }
        for (i in 0 until fftSize / 2) {
            val a = -2.0 * Math.PI * i / fftSize
            twCos[i] = cos(a).toFloat()
            twSin[i] = sin(a).toFloat()
        }
    }

    // ============================================================
    // FEED - called from the decoder, on the decode thread
    // ============================================================

    /**
     * @param pcm    16-bit little-endian PCM, exactly what goes to the AudioTrack
     * @param offset first byte to read
     * @param length how many bytes
     */
    fun feed(pcm: ByteArray, offset: Int, length: Int) {
        // FRAMES, not samples. getPlaybackHeadPosition() counts frames, so
        // this must too or every beat is out by the channel count.
        val ch = if (channels < 1) 1 else channels
        val frameBytes = ch * 2
        var i = offset
        val end = offset + length
        while (i + frameBytes <= end) {
            // downmix to mono - feeding interleaved stereo into the FFT would
            // smear the spectrum, and the onset detection would be worse
            var sum = 0f
            var c = 0
            while (c < ch) {
                val lo = pcm[i + c * 2].toInt() and 0xFF
                val hi = pcm[i + c * 2 + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt() / 32768f
                c++
            }
            i += frameBytes
            val s = sum / ch

            pending[pendingCount++] = s
            samplesFed++

            if (pendingCount >= fftSize) {
                analyseFrame()
                // slide by one hop, not one frame
                System.arraycopy(pending, hop, pending, 0, fftSize - hop)
                pendingCount = fftSize - hop
            }
        }
    }

    // ============================================================
    // ONE ANALYSIS FRAME
    // ============================================================

    private fun analyseFrame() {
        // ---- window into the FFT buffers ----
        for (n in 0 until fftSize) {
            re[n] = pending[n] * window[n]
            im[n] = 0f
        }

        fft()

        // ---- magnitude ----
        for (k in 0 until bins) {
            mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
        }

        // ---- spectral flux: only the RISES count, that is what an onset is ----
        var flux = 0f
        for (k in 0 until bins) {
            val d = mag[k] - prevMag[k]
            if (d > 0f) flux += d
            prevMag[k] = mag[k]
        }

        // ---- the centroid, for the colour field ----
        var cnum = 0f
        var cden = 0f
        var k2 = 1
        while (k2 < bins) {
            cnum += k2 * mag[k2]
            cden += mag[k2]
            k2++
        }
        if (cden > 1e-6f) {
            val c = cnum / cden / bins
            // smoothed, so the colour glides instead of flickering
            lastCentroid = lastCentroid * 0.80f + c.coerceIn(0f, 1f) * 0.20f
        }

        // ---- a slow mean, so the threshold follows the track ----
        meanMag = meanMag * 0.995f + flux * 0.005f
        val novelty = flux / (meanMag + 1e-6f)

        pushEnvelope(novelty)
        hopsSeen++

        updateTempo()
        maybeEmitBeat(novelty)
    }

    // ============================================================
    // ENVELOPE RING
    // ============================================================

    private fun pushEnvelope(v: Float) {
        envelope[envelopeWrite] = v
        envelopeWrite = (envelopeWrite + 1) % envelopeCapacity
        if (envelopeCount < envelopeCapacity) envelopeCount++
    }

    private fun envelopeAt(hopsAgo: Int): Float {
        if (hopsAgo < 0 || hopsAgo >= envelopeCount) return 0f
        var idx = envelopeWrite - 1 - hopsAgo
        while (idx < 0) idx += envelopeCapacity
        return envelope[idx]
    }

    // ============================================================
    // TEMPO AND PHASE - this is what stops beats being missed
    // ============================================================

    /**
     * Onset detection answers "did something happen just now".
     * This answers "where is the pulse" - which is the difference between
     * catching the beat and catching it when it happens to be loud.
     */
    private fun updateTempo() {
        // wait for a few seconds of history before trusting anything
        if (envelopeCount < sampleRate / hop * 3) return

        // only re-estimate occasionally - it does not change every frame
        if (hopsSeen % 32 != 0) return

        val minLag = (sampleRate / hop * 60 / 220).coerceAtLeast(4)   // 220 bpm
        val maxLag = (sampleRate / hop * 60 / 60).coerceAtMost(envelopeCount / 2)  // 60 bpm
        if (maxLag <= minLag) return

        var bestLag = tempoLagHops
        var bestScore = -1f

        var lag = minLag
        while (lag <= maxLag) {
            var s = 0f
            var n = 0
            while (n < 400 && n + lag < envelopeCount) {
                s += envelopeAt(n) * envelopeAt(n + lag)
                n++
            }
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
            lag++
        }

        if (bestLag <= 0) return

        // require a real peak, not noise
        if (bestScore <= 0f) return

        tempoLagHops = bestLag

        // ---- phase: which hop in the period the beats land on ----
        var bestPhase = phaseHops
        var bestPhaseScore = -1f
        var p = 0
        while (p < bestLag) {
            var s = 0f
            var n = p
            while (n < envelopeCount) {
                s += envelopeAt(n)
                n += bestLag
            }
            if (s > bestPhaseScore) {
                bestPhaseScore = s
                bestPhase = p
            }
            p++
        }
        phaseHops = bestPhase
    }

    // ============================================================
    // EMIT
    // ============================================================

    private fun maybeEmitBeat(novelty: Float) {
        // before the tempo locks, fall back to plain onset detection so the
        // visualiser still moves during the first couple of seconds
        if (tempoLagHops <= 0) {
            if (novelty > 1.8f && hopsSeen - lastBeatHop > 2) {
                emit(novelty)
                lastBeatHop = hopsSeen
            }
            return
        }

        // where in the period are we?
        val posInPeriod = hopsSeen % tempoLagHops
        if (posInPeriod != phaseHops) return
        if (hopsSeen - lastBeatHop < tempoLagHops / 2) return

        // the beat is predicted here. take the strength from the envelope at
        // this moment, so every wave can be a different size - and if the
        // beat is quiet, we STILL fire, which is the whole point
        val local = maxOf(novelty, envelopeAt(0), envelopeAt(1), envelopeAt(2))
        emit(local)
        lastBeatHop = hopsSeen
    }

    private fun emit(strength: Float) {
        // the sample position of the moment this frame represents
        val pos = samplesFed - (fftSize - hop)
        val s = (strength / 4f).coerceIn(0.08f, 1f)
        beats.add(Beat(pos, s, lastCentroid))
    }

    // ============================================================
    // READ
    // ============================================================

    /** How many beats have been found so far. */
    fun beatCount(): Int = beats.size

    /** Every beat found so far, in order. */
    fun all(): List<Beat> = beats

    /** Beats whose position is at or after [from], for the reader. */
    fun from(from: Long): List<Beat> {
        val out = ArrayList<Beat>(16)
        for (b in beats) if (b.samplePosition >= from) out.add(b)
        return out
    }

    fun reset() {
        envelopeCount = 0
        envelopeWrite = 0
        pendingCount = 0
        samplesFed = 0L
        beats.clear()
        tempoLagHops = 0
        phaseHops = 0
        hopsSeen = 0
        lastBeatHop = -100000
        meanMag = 0f
        lastCentroid = 0.5f
        for (k in 0 until bins) prevMag[k] = 0f
    }

    // ============================================================
    // A SMALL ITERATIVE RADIX-2 FFT
    // ============================================================

    private fun fft() {
        val n = fftSize
        // bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // butterflies
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                var t = 0
                while (k < half) {
                    val c = twCos[t]
                    val s = twSin[t]
                    val l = i + k
                    val r = l + half
                    val tr = re[r] * c - im[r] * s
                    val ti = re[r] * s + im[r] * c
                    re[r] = re[l] - tr
                    im[r] = im[l] - ti
                    re[l] += tr
                    im[l] += ti
                    k++
                    t += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
