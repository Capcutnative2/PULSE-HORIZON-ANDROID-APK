package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testWavePeaksOnBeat() {
    val launcher = WaveLauncher(44100)
    val beats = listOf(BeatTimeline.Beat(samplePosition = 1000L, strength = 0.8f, colour = 0.5f))
    launcher.setTimeline(beats)

    // Before beat reaches playback head
    launcher.advance(head = 500L, dt = 0.016f)
    assertEquals(0f, launcher.level(), 0.001f)

    // Beat reached playback head: wave launches right on the beat
    launcher.advance(head = 1000L, dt = 0.016f)
    val peakLevel = launcher.level()
    assertTrue("Wave must be at peak height on beat arrival, got $peakLevel", peakLevel > 0.5f)

    // Next frame: wave decays monotonically
    launcher.advance(head = 1000L + 705L, dt = 0.016f)
    val decayedLevel = launcher.level()
    assertTrue("Wave must decay after beat hit, got $decayedLevel vs $peakLevel", decayedLevel < peakLevel)
  }

  @Test
  fun testBeatTimelineDetectsBeatsFromPCM() {
    val sampleRate = 44100
    val channels = 1
    val timeline = BeatTimeline(sampleRate, channels)

    // Generate 3 seconds of PCM audio with distinct 120 BPM drum pulses (every 0.5s = 22050 frames)
    val durationSec = 3.5f
    val totalSamples = (durationSec * sampleRate).toInt()
    val pcmBytes = ByteArray(totalSamples * 2)

    val beatInterval = (sampleRate * 0.5).toInt()
    for (i in 0 until totalSamples) {
      val posInBeat = i % beatInterval
      val sampleVal: Short = if (posInBeat < 2000) {
        // Low-frequency sine burst representing kick (60 Hz)
        val t = posInBeat.toDouble() / sampleRate
        val env = 1.0 - (posInBeat.toDouble() / 2000.0)
        (Math.sin(2.0 * Math.PI * 60.0 * t) * 28000.0 * env).toInt().toShort()
      } else {
        0
      }
      val byteIdx = i * 2
      pcmBytes[byteIdx] = (sampleVal.toInt() and 0xFF).toByte()
      pcmBytes[byteIdx + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
    }

    // Feed in realistic buffer chunks (e.g. 4096 bytes)
    val chunkSize = 4096
    var offset = 0
    while (offset < pcmBytes.size) {
      val len = minOf(chunkSize, pcmBytes.size - offset)
      timeline.feed(pcmBytes, offset, len)
      offset += len
    }

    val beats = timeline.all()
    assertTrue("Timeline should detect beats from decoded PCM, found ${beats.size}", beats.isNotEmpty())
    // Verify detected beats carry positive strength and realistic sample position
    for (b in beats) {
      assertTrue("Beat strength must be > 0, got ${b.strength}", b.strength > 0f)
      assertTrue("Beat position must be non-negative, got ${b.samplePosition}", b.samplePosition >= 0)
    }
  }
}
