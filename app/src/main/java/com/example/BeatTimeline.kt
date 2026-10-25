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
    // tempo and onset tracking
    // ------------------------------------------------------------
    private var tempoLagHops = 0        // period of the beat, in hops
    private var phaseHops = 0           // which hop the beat lands on
    private var tempoPeriodHops = 0f    // exact floating-point period to prevent drift
    private var nextExpectedBeatHop = -1
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

        // ---- weighted spectral flux ----
        // Emphasizes rhythmic low-end (sub-bass, kicks, bass 30-300 Hz) and mid transients,
        // while attenuating high-frequency noise, hiss, and vocal sibilance (>3.5 kHz)
        var flux = 0f
        for (k in 0 until bins) {
            val d = mag[k] - prevMag[k]
            if (d > 0f) {
                val weight = when {
                    k <= 4 -> 3.5f   // ~0 - 340 Hz (kicks, 808s, bass drops)
                    k <= 14 -> 2.0f  // ~340 - 1200 Hz (snare fundamental, toms, bassline)
                    k <= 35 -> 1.0f  // ~1200 - 3000 Hz (claps, guitar strum attacks)
                    else -> 0.25f    // > 3000 Hz (hi-hats, cymbals, air, sibilance)
                }
                flux += d * weight
            }
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

        // ---- adaptive mean tracking for robust novelty across genres ----
        meanMag = meanMag * 0.992f + flux * 0.008f
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

    private fun localEnvelopeMean(count: Int = 16): Float {
        if (envelopeCount == 0) return 1f
        val n = minOf(count, envelopeCount)
        var sum = 0f
        for (i in 0 until n) {
            sum += envelopeAt(i)
        }
        return sum / n
    }

    private var lastEnergyRatio = 1f

    private data class EnergyRatio(val onEnergy: Float, val betweenEnergy: Float, val ratio: Float)

    private fun findBestPhase(lag: Int): Int {
        var bestP = 0
        var bestScore = -1f
        var p = 0
        val maxN = minOf(envelopeCount, 600)
        val w = (lag / 16).coerceIn(1, 2)
        while (p < lag) {
            var s = 0f
            var n = p
            while (n < maxN) {
                var maxLocal = 0f
                for (dw in -w..w) {
                    val idx = n + dw
                    if (idx in 0 until maxN) {
                        val v = envelopeAt(idx)
                        if (v > maxLocal) maxLocal = v
                    }
                }
                s += maxLocal
                n += lag
            }
            if (s > bestScore) {
                bestScore = s
                bestP = p
            }
            p++
        }
        return bestP
    }

    private fun measureEnergyRatio(lag: Int, phase: Int): EnergyRatio {
        if (lag <= 0 || envelopeCount == 0) return EnergyRatio(0f, 0f, 1f)
        val maxN = minOf(envelopeCount, 600)
        if (maxN < lag) return EnergyRatio(0f, 0f, 1f)

        val w = (lag / 12).coerceIn(1, 3)
        var onSum = 0f
        var onCount = 0
        var betweenSum = 0f
        var betweenCount = 0

        var n = 0
        while (n < maxN) {
            val mod = (n - phase) % lag
            val pos = if (mod < 0) mod + lag else mod
            val distToBeat = minOf(pos, lag - pos)

            val v = envelopeAt(n)
            if (distToBeat <= w) {
                onSum += v
                onCount++
            } else {
                betweenSum += v
                betweenCount++
            }
            n++
        }

        val avgOn = if (onCount > 0) onSum / onCount else 0f
        val avgBetween = if (betweenCount > 0) betweenSum / betweenCount else 0f
        val ratio = if (avgBetween > 1e-4f) avgOn / avgBetween else (avgOn / 1e-4f)
        return EnergyRatio(avgOn, avgBetween, ratio)
    }

    // ============================================================
    // TEMPO AND PHASE - tracks tempo and locks phase
    // ============================================================

    private fun updateTempo() {
        // wait for at least 2.5 seconds of history
        val minHistory = (sampleRate / hop * 2.5f).toInt()
        if (envelopeCount < minHistory) return

        // re-estimate periodically
        if (hopsSeen % 32 != 0) return

        // Support wide tempo range: 45 BPM to 220 BPM
        val minLag = (sampleRate / hop * 60 / 220).coerceAtLeast(4)   // 220 bpm
        val maxLag = (sampleRate / hop * 60 / 45).coerceAtMost(envelopeCount - 30)  // 45 bpm
        if (maxLag <= minLag) return

        var bestLag = tempoLagHops
        var bestScore = -1f

        var lag = minLag
        while (lag <= maxLag) {
            var s = 0f
            var n = 0
            val maxN = minOf(envelopeCount - lag, 600)
            while (n < maxN) {
                s += envelopeAt(n) * envelopeAt(n + lag)
                n++
            }
            val normScore = if (maxN > 0) s / maxN else 0f
            if (normScore > bestScore) {
                bestScore = normScore
                bestLag = lag
            }
            lag++
        }

        if (bestLag <= 0 || bestScore <= 0f) return

        val p1 = findBestPhase(bestLag)
        val r1 = measureEnergyRatio(bestLag, p1)

        var chosenLag = bestLag
        var chosenPhase = p1
        var chosenRatio = r1

        // Octave Verification:
        // The autocorrelation search can lock onto the half-beat (2x tempo) due to strong 8th-note
        // off-beats, or onto double-period (0.5x tempo). We verify against the audio by measuring
        // energy ON the beats vs BETWEEN the beats across octave candidates.
        val doubleLag = bestLag * 2
        if (doubleLag <= maxLag) {
            val p2 = findBestPhase(doubleLag)
            val r2 = measureEnergyRatio(doubleLag, p2)

            if ((r2.ratio > chosenRatio.ratio * 1.10f || chosenRatio.ratio < 1.40f) &&
                r2.ratio > chosenRatio.ratio && r2.onEnergy >= chosenRatio.onEnergy * 0.80f) {
                chosenLag = doubleLag
                chosenPhase = p2
                chosenRatio = r2
            }
        }

        val halfLag = bestLag / 2
        if (halfLag >= minLag) {
            val pHalf = findBestPhase(halfLag)
            val rHalf = measureEnergyRatio(halfLag, pHalf)

            if ((rHalf.ratio > chosenRatio.ratio * 1.10f || chosenRatio.ratio < 1.40f) &&
                rHalf.ratio > chosenRatio.ratio && rHalf.onEnergy >= chosenRatio.onEnergy * 0.80f) {
                chosenLag = halfLag
                chosenPhase = pHalf
                chosenRatio = rHalf
            }
        }

        lastEnergyRatio = chosenRatio.ratio

        // Honest handling: If there is NO clear beat (ambient, classical, free jazz),
        // the on/off ratio never gets high (< 1.40). DO NOT INVENT A BEAT.
        // Fall back to loudness/onset reaction.
        if (chosenRatio.ratio < 1.40f) {
            tempoLagHops = 0
            tempoPeriodHops = 0f
            phaseHops = 0
            return
        }

        tempoLagHops = chosenLag
        tempoPeriodHops = chosenLag.toFloat()
        phaseHops = chosenPhase

        // Align next expected beat with the measured phase
        var nextHop = hopsSeen + (chosenLag - chosenPhase % chosenLag)
        while (nextHop <= hopsSeen) {
            nextHop += chosenLag
        }
        nextExpectedBeatHop = nextHop
    }

    // ============================================================
    // EMIT - ONSET-LOCKING + CONTINUOUS CARRIER CLOCK
    // ============================================================

    private fun maybeEmitBeat(novelty: Float) {
        val isLocalPeak = novelty > envelopeAt(1) && novelty > envelopeAt(2)
        val localMean = localEnvelopeMean(16)
        val isOnset = isLocalPeak && novelty > (localMean * 1.30f).coerceAtLeast(0.35f)
        val minInterval = if (tempoPeriodHops > 0f) (tempoPeriodHops * 0.45f).toInt() else 12

        if (tempoPeriodHops <= 0f) {
            // Before tempo lock: respond dynamically to detected onsets
            if (isOnset && (hopsSeen - lastBeatHop >= minInterval)) {
                emit(novelty)
                lastBeatHop = hopsSeen
            }
            return
        }

        val window = (tempoPeriodHops * 0.25f).toInt().coerceAtLeast(2)
        val distToExpected = hopsSeen - nextExpectedBeatHop

        if (abs(distToExpected) <= window && isOnset) {
            // Real onset in expected window: lock directly onto true audio transient (0 ms drift)
            val strength = maxOf(novelty, envelopeAt(0), envelopeAt(1))
            emit(strength)
            lastBeatHop = hopsSeen
            nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
        } else if (hopsSeen >= nextExpectedBeatHop && (hopsSeen - lastBeatHop >= minInterval)) {
            // Predicted beat deadline reached: carries through quiet parts/sustained notes
            val strength = maxOf(novelty, envelopeAt(0), envelopeAt(1), envelopeAt(2))
            emit(strength)
            lastBeatHop = hopsSeen
            nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
        } else if (isOnset && (novelty > localMean * 2.2f) && (hopsSeen - lastBeatHop >= minInterval)) {
            // Prominent off-grid syncopation or tempo shift: adaptively align clock
            emit(novelty)
            lastBeatHop = hopsSeen
            nextExpectedBeatHop = (hopsSeen + tempoPeriodHops).toInt()
        }
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

    /** Current locked BPM, or 0f if not locked / no clear beat */
    fun currentBpm(): Float {
        if (tempoPeriodHops <= 0f) return 0f
        return (sampleRate.toFloat() / hop * 60f) / tempoPeriodHops
    }

    /** Ratio of energy on the beats vs between the beats */
    fun currentEnergyRatio(): Float = lastEnergyRatio

    /** Whether tempo has locked onto a periodic beat grid */
    fun tempoLocked(): Boolean = tempoPeriodHops > 0f

    fun reset() {
        envelopeCount = 0
        envelopeWrite = 0
        pendingCount = 0
        samplesFed = 0L
        beats.clear()
        tempoLagHops = 0
        phaseHops = 0
        tempoPeriodHops = 0f
        nextExpectedBeatHop = -1
        hopsSeen = 0
        lastBeatHop = -100000
        meanMag = 0f
        lastCentroid = 0.5f
        lastEnergyRatio = 1f
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
