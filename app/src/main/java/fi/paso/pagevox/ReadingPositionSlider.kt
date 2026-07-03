package fi.paso.pagevox

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ReadingPositionSlider(
    totalSentences: Int,
    currentIndex: Int,
    onSeekChange: (Int) -> Unit,    // fires during drag (continuous preview)
    onSeekFinished: (Int) -> Unit,  // fires on tap or drag release (commit)
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (totalSentences <= 0) return@BoxWithConstraints

        var dragging by remember { mutableStateOf(false) }
        var localIndex by remember { mutableIntStateOf(currentIndex.coerceAtLeast(0)) }

        // Track TTS-driven index updates only when the user isn't actively
        // dragging — otherwise progressions would yank the thumb away.
        LaunchedEffect(currentIndex) {
            if (!dragging) localIndex = currentIndex.coerceAtLeast(0)
        }

        val maxIdx = (totalSentences - 1).coerceAtLeast(0)
        val safeIndex = localIndex.coerceIn(0, maxIdx)
        val fraction = if (maxIdx == 0) 0f else safeIndex.toFloat() / maxIdx

        val barHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        fun yToIndex(y: Float): Int {
            if (maxIdx == 0) return 0
            val f = (y / barHeightPx).coerceIn(0f, 1f)
            return (f * maxIdx).roundToInt()
        }

        // Filled track from top to thumb.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        )

        // Thumb marker.
        Box(
            Modifier
                .fillMaxWidth()
                .offset(y = (maxHeight * fraction) - 3.dp)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary)
        )

        // Position label, rotated to run along the narrow bar: "current / total".
        val label = buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append("${safeIndex + 1}")
            }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append(" / $totalSentences")
            }
        }
        Text(
            text = label,
            modifier = Modifier.align(Alignment.Center).verticalText(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )

        // Touch overlay: tap to jump, drag to scrub.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(totalSentences) {
                    detectTapGestures { offset ->
                        val idx = yToIndex(offset.y)
                        localIndex = idx
                        onSeekFinished(idx)
                    }
                }
                .pointerInput(totalSentences) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val idx = yToIndex(offset.y)
                            localIndex = idx
                            onSeekChange(idx)
                        },
                        onDragEnd = {
                            dragging = false
                            onSeekFinished(localIndex)
                        },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            val idx = yToIndex(change.position.y)
                            if (idx != localIndex) {
                                localIndex = idx
                                onSeekChange(idx)
                            }
                        }
                    )
                }
        )
    }
}

/**
 * Lays a composable out rotated 90° counter-clockwise. The content is measured
 * against the parent's *height* (so text won't wrap in a narrow column) and the
 * node reports swapped dimensions, then graphicsLayer performs the rotation. The
 * result reads bottom-to-top and occupies only the content's line height in width.
 */
private fun Modifier.verticalText(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        )
        layout(placeable.height, placeable.width) {
            placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2)
            )
        }
    }
    .graphicsLayer(rotationZ = -90f)
