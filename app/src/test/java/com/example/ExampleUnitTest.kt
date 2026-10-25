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
}
