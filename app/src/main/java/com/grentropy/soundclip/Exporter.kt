@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.grentropy.soundclip

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportParams(
    val mediaUri: Uri,
    val isVideo: Boolean,
    val clipMs: Long,
    val songUri: Uri,
    val songStartMs: Long,
    val songEndMs: Long,
    val musicVolume: Float,
    val originalVolume: Float,
    val fadeIn: Boolean,
    val fadeOut: Boolean,
)

object Exporter {

    private fun buildComposition(p: ExportParams): Composition {
        val primary: EditedMediaItem = if (p.isVideo) {
            val b = EditedMediaItem.Builder(MediaItem.fromUri(p.mediaUri))
            if (p.originalVolume <= 0.001f) {
                b.setRemoveAudio(true)
            } else if (p.originalVolume < 0.999f) {
                b.setEffects(
                    Effects(
                        listOf<AudioProcessor>(
                            GainFadeProcessor(p.originalVolume, false, false, p.clipMs * 1000)
                        ),
                        emptyList()
                    )
                )
            }
            b.build()
        } else {
            val image = MediaItem.Builder()
                .setUri(p.mediaUri)
                .setImageDurationMs(p.clipMs)
                .build()
            EditedMediaItem.Builder(image)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(1080))))
                .build()
        }

        val musicDurUs = (p.songEndMs - p.songStartMs) * 1000
        val music = MediaItem.Builder()
            .setUri(p.songUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(p.songStartMs)
                    .setEndPositionMs(p.songEndMs)
                    .build()
            )
            .build()
        val musicEdited = EditedMediaItem.Builder(music)
            .setRemoveVideo(true)
            .setEffects(
                Effects(
                    listOf<AudioProcessor>(
                        GainFadeProcessor(p.musicVolume, p.fadeIn, p.fadeOut, musicDurUs)
                    ),
                    emptyList()
                )
            )
            .build()

        return Composition.Builder(
            EditedMediaItemSequence(primary),
            EditedMediaItemSequence(musicEdited),
        )
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    /** Runs the export and returns the MediaStore Uri of the saved video. */
    suspend fun export(context: Context, p: ExportParams, onProgress: (Int) -> Unit): Uri {
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        done.completeExceptionally(exportException)
                    }
                })
                .build()
            transformer.start(buildComposition(p), out.absolutePath)
            val holder = ProgressHolder()
            try {
                while (!done.isCompleted) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                    }
                    delay(200)
                }
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                saveToMovies(context, out)
            } finally {
                out.delete()
            }
        }
    }

    private fun saveToMovies(context: Context, file: File): Uri {
        val name = "Soundclip_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Soundclip")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create file in Movies")
        resolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { it.copyTo(os) }
        } ?: error("Could not write file")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
