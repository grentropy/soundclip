@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.grentropy.soundclip

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Applies a fixed volume plus optional linear fade-in / fade-out to PCM audio.
 * Frame counting starts at the beginning of the (clipped) item.
 */
class GainFadeProcessor(
    private val gain: Float,
    private val fadeIn: Boolean,
    private val fadeOut: Boolean,
    private val durationUs: Long,
    private val fadeUs: Long = FADE_US,
) : BaseAudioProcessor() {

    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val channels = format.channelCount.coerceAtLeast(1)
        val rate = format.sampleRate
        val frames = remaining / (bytesPerSample * channels)
        val out = replaceOutputBuffer(frames * bytesPerSample * channels)

        for (f in 0 until frames) {
            val g = gainAt(framesDone + f, rate)
            for (c in 0 until channels) {
                if (isFloat) {
                    out.putFloat(inputBuffer.getFloat() * g)
                } else {
                    val s = (inputBuffer.getShort() * g).roundToInt().coerceIn(-32768, 32767)
                    out.putShort(s.toShort())
                }
            }
        }
        framesDone += frames
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun gainAt(frame: Long, sampleRate: Int): Float {
        if (sampleRate <= 0) return gain
        val tUs = frame * 1_000_000L / sampleRate
        return gain * fadeFactor(tUs, durationUs, fadeIn, fadeOut, fadeUs)
    }

    override fun onFlush() {
        framesDone = 0
    }

    override fun onReset() {
        framesDone = 0
    }

    companion object {
        const val FADE_US = 1_500_000L

        /** Shared by export and preview so both sound the same. */
        fun fadeFactor(tUs: Long, durationUs: Long, fadeIn: Boolean, fadeOut: Boolean, fadeUs: Long = FADE_US): Float {
            val f = fadeUs.coerceAtMost(durationUs / 2).coerceAtLeast(1)
            var g = 1f
            if (fadeIn && tUs < f) g = minOf(g, tUs.toFloat() / f)
            val left = durationUs - tUs
            if (fadeOut && left < f) g = minOf(g, left.coerceAtLeast(0).toFloat() / f)
            return g.coerceIn(0f, 1f)
        }
    }
}
