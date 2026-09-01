package fi.paso.pagevox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The side panel shown next to the page on a wide screen: where the reading is
 * right now, and the page's headings as a jump list.
 *
 * Everything here is derived from data the reader already has — the sentence
 * list, the section starts that drive the scrubber ticks, and the duration
 * estimates behind the silent track — so the panel costs nothing extra to fill
 * and stays correct while the service reads in the background.
 *
 * It is deliberately wide-layout-only. On a phone the same information is
 * already carried by the scrubber (heading above the bar, section ticks on the
 * track) and by long-pressing the skip buttons; a panel there would just cover
 * the page.
 */
@Composable
fun ContentsPanel(
    sentences: List<String>,
    sectionStarts: List<Int>,
    sectionTitleAt: (Int) -> String?,
    currentIndex: Int,
    remainingMsFrom: (Int) -> Long,
    speechRate: Float,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (sentences.isEmpty()) {
        EmptyPanelMessage(stringResource(R.string.contents_nothing_loaded), modifier)
        return
    }

    val index = currentIndex.coerceIn(0, sentences.lastIndex)
    val fraction = (index + 1).toFloat() / sentences.size
    // Stored duration estimates are for 1x; what is actually left depends on the
    // rate the engine is set to right now — same correction the library makes.
    val remaining = (remainingMsFrom(index) / speechRate.coerceAtLeast(0.1f)).toLong()
    // Which heading we are under, as a position in [sectionStarts] rather than a
    // sentence index, so the list can highlight and scroll to it.
    val activeSection = remember(index, sectionStarts) {
        sectionStarts.indexOfLast { it <= index }
    }

    Column(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(
                    R.string.progress_summary,
                    (fraction * 100).roundToInt(),
                    timeLeftText(remaining)
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            // The one place with room to show the sentence being read in full —
            // the scrubber's preview is a single clipped line.
            Text(
                text = sentences[index],
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.sentence_counter, index + 1, sentences.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        HorizontalDivider()

        if (sectionStarts.isEmpty()) {
            EmptyPanelMessage(stringResource(R.string.contents_no_sections))
            return@Column
        }

        val listState = rememberLazyListState()
        // Follow the reading down the list, so a long page's current chapter
        // stays on screen without the user chasing it.
        LaunchedEffect(activeSection) {
            if (activeSection >= 0) listState.animateScrollToItem(activeSection)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(sectionStarts) { position, start ->
                val active = position == activeSection
                Text(
                    text = sectionTitleAt(start).orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onSeek(start) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyPanelMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(32.dp)
    )
}
