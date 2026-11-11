package fi.paso.pagevox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How far the wave rises and falls while reading, as a fraction of its full
 *  height. Gentle: it says "reading", it shouldn't pull the eye off the page. */
private const val WAVE_AMPLITUDE = 0.7f

/**
 * The reading position, as a card just above the playback controls. Drag or
 * tap the bar to move the reading position; the sentence under the thumb is
 * previewed live above the bar, and on release playback (re)starts from there.
 * Doubles as the "now reading" readout while playing (the thumb tracks TTS
 * progress when the user isn't scrubbing). Renders nothing until a page has been
 * extracted into sentences.
 *
 * Pages with headings also get section marks on the track and the current
 * section's heading above the sentence preview, so a long article reads as a set
 * of chapters rather than one undifferentiated bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HorizontalReadingScrubber(
    totalSentences: Int,
    currentIndex: Int,
    sentences: List<String>,
    sectionStarts: List<Int>,
    sectionTitleAt: (Int) -> String?,
    onSeekChange: (Int) -> Unit,    // during drag: live scroll preview
    onSeekFinished: (Int) -> Unit,  // on release/tap: commit playback
    playing: Boolean,               // the track waves while reading, lies flat when paused
    modifier: Modifier = Modifier
) {
    if (totalSentences <= 0) return
    val maxIdx = (totalSentences - 1).coerceAtLeast(0)

    var dragging by remember { mutableStateOf(false) }
    // State-based, because in material3 1.5 that is the only Slider that takes a
    // custom track. Rebuilt when the page's length changes, which is a new range.
    val sliderState = remember(maxIdx) {
        SliderState(currentIndex.coerceIn(0, maxIdx).toFloat(), 0, 0f..maxIdx.toFloat())
    }
    // Only re-issue the live-scroll preview when the rounded sentence actually
    // changes, so a single drag doesn't fire a window.find on every pixel.
    var lastEmitted by remember { mutableIntStateOf(-1) }

    // Track TTS-driven progress only while the user isn't actively scrubbing,
    // otherwise the thumb would fight the finger.
    LaunchedEffect(currentIndex, sliderState) {
        if (!dragging) sliderState.value = currentIndex.coerceIn(0, maxIdx).toFloat()
    }

    val shownIndex = sliderState.value.roundToInt().coerceIn(0, maxIdx)
    val previewText = sentences.getOrNull(shownIndex).orEmpty()
    val sectionTitle = sectionTitleAt(shownIndex)

    // A card above the controls rather than a full-width band: Material 3
    // Expressive groups related things in rounded containers.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
            if (sectionTitle != null) {
                Text(
                    text = sectionTitle,
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = previewText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.sentence_counter, shownIndex + 1, totalSentences),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (maxIdx > 0) {
                Slider(
                    state = sliderState,
                    onValueChange = { v ->
                        dragging = true
                        sliderState.value = v
                        val idx = v.roundToInt().coerceIn(0, maxIdx)
                        if (idx != lastEmitted) {
                            lastEmitted = idx
                            onSeekChange(idx)
                        }
                    },
                    onValueChangeFinished = {
                        onSeekFinished(sliderState.value.roundToInt().coerceIn(0, maxIdx))
                        dragging = false
                        lastEmitted = -1
                    },
                    track = { state ->
                        ScrubberTrack(state, maxIdx, sectionStarts, waving = playing && !dragging)
                    }
                )
            }
        }
    }
}

/**
 * The reading progress as a wave, the way Android draws a playing media item: it
 * moves while reading and settles into a flat line on pause, so the state shows
 * even with the sound down. Section marks are drawn over it rather than through
 * the Slider's own `steps`, which are evenly spaced *and* quantize the value;
 * sections are neither evenly spaced nor a constraint on where you may seek.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScrubberTrack(state: SliderState, maxIdx: Int, sectionStarts: List<Int>, waving: Boolean) {
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Keyed on the state: the indicator only asks for its amplitude again when
        // the progress moves, and on pause the progress stops moving, so the wave
        // stayed up. A fresh indicator picks up the new amplitude at once. Paused,
        // the wave's travel stops too: a flat line has nothing to move, and moving
        // it anyway would redraw the card every frame for as long as it sits there.
        key(waving) {
            LinearWavyProgressIndicator(
                progress = { if (maxIdx > 0) (state.value / maxIdx).coerceIn(0f, 1f) else 0f },
                amplitude = { if (waving) WAVE_AMPLITUDE else 0f },
                waveSpeed = if (waving) WavyProgressIndicatorDefaults.LinearDeterminateWavelength else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (sectionStarts.isNotEmpty()) {
            Canvas(Modifier.matchParentSize()) {
                val radius = 2.dp.toPx()
                sectionStarts.forEach { start ->
                    // A mark at sentence 0 would sit on the track's rounded start; skip it.
                    if (start <= 0 || start > maxIdx) return@forEach
                    val x = (start.toFloat() / maxIdx) * size.width
                    drawCircle(markColor, radius, Offset(x, size.height / 2))
                }
            }
        }
    }
}
