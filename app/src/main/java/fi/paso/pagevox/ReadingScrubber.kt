package fi.paso.pagevox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Full-width horizontal scrubber shown just above the playback controls. Drag or
 * tap the bar to move the reading position; the sentence under the thumb is
 * previewed live above the bar, and on release playback (re)starts from there.
 * Doubles as the "now reading" readout while playing (the thumb tracks TTS
 * progress when the user isn't scrubbing). Renders nothing until a page has been
 * extracted into sentences.
 */
@Composable
fun HorizontalReadingScrubber(
    totalSentences: Int,
    currentIndex: Int,
    sentences: List<String>,
    onSeekChange: (Int) -> Unit,    // during drag: live scroll preview
    onSeekFinished: (Int) -> Unit,  // on release/tap: commit playback
    modifier: Modifier = Modifier
) {
    if (totalSentences <= 0) return
    val maxIdx = (totalSentences - 1).coerceAtLeast(0)

    var dragging by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(currentIndex.coerceAtLeast(0).toFloat()) }
    // Only re-issue the live-scroll preview when the rounded sentence actually
    // changes, so a single drag doesn't fire a window.find on every pixel.
    var lastEmitted by remember { mutableIntStateOf(-1) }

    // Track TTS-driven progress only while the user isn't actively scrubbing,
    // otherwise the thumb would fight the finger.
    LaunchedEffect(currentIndex) {
        if (!dragging) sliderPos = currentIndex.coerceAtLeast(0).toFloat()
    }

    val shownIndex = sliderPos.roundToInt().coerceIn(0, maxIdx)
    val previewText = sentences.getOrNull(shownIndex).orEmpty()

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
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
                    text = "${shownIndex + 1} / $totalSentences",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (maxIdx > 0) {
                Slider(
                    value = sliderPos.coerceIn(0f, maxIdx.toFloat()),
                    onValueChange = { v ->
                        dragging = true
                        sliderPos = v
                        val idx = v.roundToInt().coerceIn(0, maxIdx)
                        if (idx != lastEmitted) {
                            lastEmitted = idx
                            onSeekChange(idx)
                        }
                    },
                    onValueChangeFinished = {
                        onSeekFinished(sliderPos.roundToInt().coerceIn(0, maxIdx))
                        dragging = false
                        lastEmitted = -1
                    },
                    valueRange = 0f..maxIdx.toFloat()
                )
            }
        }
    }
}
