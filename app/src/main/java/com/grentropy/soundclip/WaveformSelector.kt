package com.grentropy.soundclip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun WaveformSelector(
    peaks: FloatArray,
    songMs: Long,
    clipMs: Long,
    startMs: Long,
    playheadMs: Long,
    onStartChange: (Long) -> Unit,
) {
    val selected = MaterialTheme.colorScheme.primary
    val unselected = MaterialTheme.colorScheme.outlineVariant
    val boxFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val boxEdge = MaterialTheme.colorScheme.primary
    val playColor = MaterialTheme.colorScheme.tertiary

    val currentStart by rememberUpdatedState(startMs)
    val currentClip by rememberUpdatedState(clipMs)
    val currentSong by rememberUpdatedState(songMs)
    val change by rememberUpdatedState(onStartChange)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val song = currentSong.coerceAtLeast(1L)
                    val centre = (pos.x / size.width * song).toLong()
                    change(centre - currentClip / 2)
                }
            }
            .pointerInput(Unit) {
                var dragStart = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragStart = currentStart.toFloat() },
                ) { input, dx ->
                    input.consume()
                    val song = currentSong.coerceAtLeast(1L)
                    dragStart += dx / size.width * song
                    change(dragStart.toLong())
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val song = songMs.coerceAtLeast(1L).toFloat()
        val n = peaks.size
        val step = w / n
        val boxL = startMs / song * w
        val boxR = ((startMs + clipMs).coerceAtMost(songMs)) / song * w

        drawRoundRect(boxFill, Offset(boxL, 0f), Size(boxR - boxL, h), CornerRadius(6f, 6f))

        val barW = (step * 0.7f).coerceAtLeast(1f)
        for (i in 0 until n) {
            val x = i * step + step / 2
            val bh = peaks[i] * (h * 0.9f)
            val inside = x in boxL..boxR
            drawLine(
                color = if (inside) selected else unselected,
                start = Offset(x, (h - bh) / 2),
                end = Offset(x, (h + bh) / 2),
                strokeWidth = barW,
            )
        }

        drawRoundRect(
            boxEdge, Offset(boxL, 0f), Size(boxR - boxL, h), CornerRadius(6f, 6f),
            style = Stroke(width = 2.dp.toPx())
        )

        if (playheadMs >= 0) {
            val px = playheadMs / song * w
            drawLine(playColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2.dp.toPx())
        }
    }
}
