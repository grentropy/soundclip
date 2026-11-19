@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.grentropy.soundclip

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SoundclipTheme { SoundclipApp() } }
    }
}

@Composable
fun SoundclipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

data class PickedMedia(val uri: Uri, val isVideo: Boolean, val durationMs: Long)
data class PickedSong(val uri: Uri, val name: String, val durationMs: Long)

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Int) : ExportState
    data class Done(val uri: Uri) : ExportState
    data class Failed(val message: String) : ExportState
}

@Composable
fun SoundclipApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var media by remember { mutableStateOf<PickedMedia?>(null) }
    var photoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoSeconds by remember { mutableFloatStateOf(15f) }

    var song by remember { mutableStateOf<PickedSong?>(null) }
    var wave by remember { mutableStateOf<FloatArray?>(null) }
    var waveLoading by remember { mutableStateOf(false) }
    var startMs by remember { mutableLongStateOf(0L) }

    var musicVol by remember { mutableFloatStateOf(1f) }
    var origVol by remember { mutableFloatStateOf(0.3f) }
    var fadeIn by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableStateOf(true) }

    var previewing by remember { mutableStateOf(false) }
    var playheadMs by remember { mutableLongStateOf(-1L) }
    var exportState by remember { mutableStateOf<ExportState>(ExportState.Idle) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val videoPlayer = remember { ExoPlayer.Builder(ctx).build() }
    val musicPlayer = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) {
        onDispose { videoPlayer.release(); musicPlayer.release() }
    }

    val clipMs: Long = media?.let { if (it.isVideo) it.durationMs else (photoSeconds * 1000).toLong() } ?: 0L
    val songMs = song?.durationMs ?: 0L
    val maxStart = (songMs - clipMs).coerceAtLeast(0L)
    fun setStart(v: Long) { startMs = v.coerceIn(0L, maxStart) }
    LaunchedEffect(maxStart) { if (startMs > maxStart) startMs = maxStart }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            previewing = false
            exportState = ExportState.Idle
            errorText = null
            scope.launch {
                val isVideo = MediaUtils.isVideo(ctx, uri)
                val dur = if (isVideo) withContext(Dispatchers.IO) { MediaUtils.durationMs(ctx, uri) } else 0L
                if (isVideo && dur <= 0) { errorText = "Couldn't read that video's length."; return@launch }
                photoBitmap = if (isVideo) null else try {
                    withContext(Dispatchers.IO) { MediaUtils.loadPreviewBitmap(ctx, uri) }
                } catch (e: Exception) { null }
                media = PickedMedia(uri, isVideo, dur)
                if (isVideo) {
                    videoPlayer.setMediaItem(MediaItem.fromUri(uri))
                    videoPlayer.prepare()
                }
            }
        }
    }

    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            previewing = false
            exportState = ExportState.Idle
            errorText = null
            scope.launch {
                val dur = withContext(Dispatchers.IO) { MediaUtils.durationMs(ctx, uri) }
                if (dur <= 0) { errorText = "Couldn't read that audio file."; return@launch }
                val name = withContext(Dispatchers.IO) { MediaUtils.displayName(ctx, uri) }
                song = PickedSong(uri, name, dur)
                startMs = 0L
                wave = null
                musicPlayer.setMediaItem(MediaItem.fromUri(uri))
                musicPlayer.prepare()
                waveLoading = true
                wave = try { MediaUtils.waveform(ctx, uri) } catch (e: Exception) { FloatArray(600) { 0.3f } }
                waveLoading = false
            }
        }
    }

    // Live volume for the original video sound during preview.
    LaunchedEffect(origVol) { videoPlayer.volume = origVol }

    // Preview: play the selected section of the song alongside the video.
    LaunchedEffect(previewing) {
        if (!previewing) {
            musicPlayer.pause(); videoPlayer.pause(); playheadMs = -1L
            return@LaunchedEffect
        }
        val m = media
        val s = song
        if (m == null || s == null) { previewing = false; return@LaunchedEffect }
        val from = startMs
        val len = clipMs.coerceAtMost(s.durationMs - from)
        musicPlayer.volume = if (fadeIn) 0f else musicVol
        musicPlayer.seekTo(from)
        musicPlayer.play()
        if (m.isVideo) { videoPlayer.seekTo(0); videoPlayer.play() }
        while (true) {
            val pos = musicPlayer.currentPosition
            val t = (pos - from).coerceAtLeast(0L)
            playheadMs = pos
            musicPlayer.volume = musicVol * GainFadeProcessor.fadeFactor(t * 1000, len * 1000, fadeIn, fadeOut)
            if (t >= len || musicPlayer.playbackState == Player.STATE_ENDED) break
            delay(40)
        }
        previewing = false
    }

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Soundclip", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

            // ---------- 1. Photo or video ----------
            val m = media
            if (m == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Start by picking a photo or video.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }) { Text("Pick photo or video") }
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .aspectRatio(if (m.isVideo) 9f / 16f else (photoBitmap?.let { it.width.toFloat() / it.height } ?: 1f), matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center
                ) {
                    if (m.isVideo) {
                        AndroidView(
                            factory = { c ->
                                PlayerView(c).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    player = videoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val b = photoBitmap
                        if (b != null) {
                            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else {
                            Text("Photo selected")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (m.isVideo) "Video · ${formatMs(m.durationMs)}" else "Photo",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Text("Change") }
                }
                if (!m.isVideo) {
                    Text("Length: ${photoSeconds.toInt()} s", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = photoSeconds,
                        onValueChange = { photoSeconds = it },
                        valueRange = 3f..60f,
                        steps = 56
                    )
                }
            }

            // ---------- 2. Song ----------
            if (m != null) {
                val s = song
                if (s == null) {
                    Button(onClick = { pickSong.launch(arrayOf("audio/*")) }) { Text("Pick a song") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatMs(s.durationMs), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { pickSong.launch(arrayOf("audio/*")) }) { Text("Change") }
                    }

                    val w = wave
                    if (w == null || waveLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(110.dp)) {
                            CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Reading song…")
                        }
                    } else {
                        Text(
                            "Drag the highlighted box, or tap, to choose the part of the song.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        WaveformSelector(
                            peaks = w,
                            songMs = s.durationMs,
                            clipMs = clipMs,
                            startMs = startMs,
                            playheadMs = playheadMs,
                            onStartChange = { setStart(it) },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { setStart(startMs - 1000) }) { Text("−1s") }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { setStart(startMs - 100) }) { Text("−0.1") }
                            Text(
                                "${formatMs(startMs)} – ${formatMs((startMs + clipMs).coerceAtMost(s.durationMs))}",
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.labelLarge
                            )
                            OutlinedButton(onClick = { setStart(startMs + 100) }) { Text("+0.1") }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { setStart(startMs + 1000) }) { Text("+1s") }
                        }
                        if (clipMs > s.durationMs) {
                            Text(
                                "The song is shorter than your ${if (m.isVideo) "video" else "photo length"}; the music will end early.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // ---------- 3. Mix ----------
                        Text("Music volume", style = MaterialTheme.typography.labelLarge)
                        Slider(value = musicVol, onValueChange = { musicVol = it })
                        if (m.isVideo) {
                            Text("Original video sound", style = MaterialTheme.typography.labelLarge)
                            Slider(value = origVol, onValueChange = { origVol = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade in", Modifier.weight(1f))
                            Switch(checked = fadeIn, onCheckedChange = { fadeIn = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fade out", Modifier.weight(1f))
                            Switch(checked = fadeOut, onCheckedChange = { fadeOut = it })
                        }

                        // ---------- 4. Preview / export ----------
                        val running = exportState is ExportState.Running
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { previewing = !previewing },
                                enabled = !running,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (previewing) "Stop" else "Preview") }
                            Button(
                                onClick = {
                                    previewing = false
                                    val params = ExportParams(
                                        mediaUri = m.uri,
                                        isVideo = m.isVideo,
                                        clipMs = clipMs,
                                        songUri = s.uri,
                                        songStartMs = startMs,
                                        songEndMs = (startMs + clipMs).coerceAtMost(s.durationMs),
                                        musicVolume = musicVol,
                                        originalVolume = if (m.isVideo) origVol else 0f,
                                        fadeIn = fadeIn,
                                        fadeOut = fadeOut,
                                    )
                                    exportState = ExportState.Running(0)
                                    exportJob = scope.launch {
                                        exportState = try {
                                            val uri = Exporter.export(ctx, params) { p -> exportState = ExportState.Running(p) }
                                            ExportState.Done(uri)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            ExportState.Idle
                                        } catch (e: Exception) {
                                            ExportState.Failed(e.cause?.message ?: e.message ?: e.toString())
                                        }
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.weight(1f)
                            ) { Text("Save video") }
                        }

                        when (val st = exportState) {
                            is ExportState.Running -> {
                                LinearProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Saving… ${st.progress}%", Modifier.weight(1f))
                                    TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel") }
                                }
                            }
                            is ExportState.Done -> {
                                Text("Saved to Movies/Soundclip.", color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = {
                                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(st.uri, "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        runCatching { ctx.startActivity(i) }
                                    }) { Text("Open") }
                                    Button(onClick = {
                                        val i = Intent(Intent.ACTION_SEND).setType("video/mp4")
                                            .putExtra(Intent.EXTRA_STREAM, st.uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        runCatching { ctx.startActivity(Intent.createChooser(i, "Share video")) }
                                    }) { Text("Share") }
                                }
                            }
                            is ExportState.Failed -> Text(
                                "Couldn't save: ${st.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                            ExportState.Idle -> {}
                        }
                    }
                }
            }

            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
