package com.grentropy.soundclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

object MediaUtils {

    fun durationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val s = c.getString(0)
                    if (!s.isNullOrBlank()) return s.substringBeforeLast('.')
                }
            }
        return uri.lastPathSegment ?: "Song"
    }

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    fun loadPreviewBitmap(context: Context, uri: Uri, maxSide: Int = 1440): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxSide.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        }
    }

    /**
     * Decodes the whole song and returns [bars] peak values in 0..1.
     */
    suspend fun waveform(context: Context, uri: Uri, bars: Int = 600): FloatArray =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) {
                extractor.release()
                error("No audio track found")
            }
            extractor.selectTrack(track)
            var durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs <= 0) durUs = durationMs(context, uri) * 1000
            if (durUs <= 0) durUs = 1

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val peaks = FloatArray(bars)
            val info = MediaCodec.BufferInfo()
            var isFloat = false
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    } else if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val ordered = ob.slice().order(ByteOrder.nativeOrder())
                            var peak = 0f
                            if (isFloat) {
                                val fb = ordered.asFloatBuffer()
                                var i = 0
                                while (i < fb.limit()) { peak = max(peak, abs(fb.get(i))); i += 8 }
                            } else {
                                val sb = ordered.asShortBuffer()
                                var i = 0
                                while (i < sb.limit()) { peak = max(peak, abs(sb.get(i).toInt()) / 32768f); i += 8 }
                            }
                            val idx = ((info.presentationTimeUs.toDouble() / durUs) * bars).toInt().coerceIn(0, bars - 1)
                            if (peak > peaks[idx]) peaks[idx] = peak
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
                extractor.release()
            }
            // Fill empty buckets from neighbours, then normalise.
            for (i in 1 until bars) if (peaks[i] == 0f) peaks[i] = peaks[i - 1] * 0.9f
            val top = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
            FloatArray(bars) { (peaks[it] / top).coerceIn(0.03f, 1f) }
        }
}

fun formatMs(ms: Long): String {
    val totalTenths = ms / 100
    val m = totalTenths / 600
    val s = (totalTenths / 10) % 60
    val t = totalTenths % 10
    return "%d:%02d.%d".format(m, s, t)
}
