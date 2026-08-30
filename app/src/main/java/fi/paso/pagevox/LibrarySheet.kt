package fi.paso.pagevox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val TAB_CONTINUE = 0
private const val TAB_BOOKMARKS = 1
private const val TAB_HISTORY = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    continueListening: List<WebPage>,
    bookmarks: List<WebPage>,
    history: List<WebPage>,
    speechRate: Float,
    onOpen: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Open on whatever the user most likely came for: a half-finished page if
    // there is one, otherwise the bookmarks list as before.
    var tab by remember {
        mutableIntStateOf(if (continueListening.isNotEmpty()) TAB_CONTINUE else TAB_BOOKMARKS)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == TAB_CONTINUE, onClick = { tab = TAB_CONTINUE },
                text = { Text(stringResource(R.string.tab_continue)) },
                icon = { Icon(Icons.Default.Headphones, contentDescription = null) })
            Tab(selected = tab == TAB_BOOKMARKS, onClick = { tab = TAB_BOOKMARKS },
                text = { Text(stringResource(R.string.tab_bookmarks)) },
                icon = { Icon(Icons.Default.Bookmark, contentDescription = null) })
            Tab(selected = tab == TAB_HISTORY, onClick = { tab = TAB_HISTORY },
                text = { Text(stringResource(R.string.tab_history)) },
                icon = { Icon(Icons.Default.History, contentDescription = null) })
        }

        val entries = when (tab) {
            TAB_CONTINUE -> continueListening
            TAB_BOOKMARKS -> bookmarks
            else -> history
        }

        if (tab == TAB_HISTORY && history.isNotEmpty()) {
            TextButton(
                onClick = onClearHistory,
                modifier = Modifier.align(Alignment.End).padding(end = 8.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.clear_history))
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = stringResource(
                    when (tab) {
                        TAB_CONTINUE -> R.string.empty_continue
                        TAB_BOOKMARKS -> R.string.empty_bookmarks
                        else -> R.string.empty_history
                    }
                ),
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(entries, key = { it.url }) { page ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = page.title.ifBlank { page.url },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            if (tab == TAB_CONTINUE) {
                                ProgressSummary(page, speechRate)
                            } else {
                                Text(
                                    text = page.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        trailingContent = if (tab == TAB_BOOKMARKS) {
                            {
                                IconButton(onClick = { onRemoveBookmark(page.url) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        stringResource(R.string.remove_bookmark)
                                    )
                                }
                            }
                        } else null,
                        modifier = Modifier.clickable { onOpen(page.url) }
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** How far into a page reading got, and roughly how much of it is left. Both
 *  come off the history entry, so this renders without loading the page. */
@Composable
private fun ProgressSummary(page: WebPage, speechRate: Float) {
    val fraction = (page.position.toFloat() / page.sentenceCount).coerceIn(0f, 1f)
    // remainingMs is stored at 1×; what's actually left depends on how fast the
    // user has the engine set right now.
    val remaining = (page.remainingMs / speechRate.coerceAtLeast(0.1f)).toLong()
    Column {
        Text(
            text = stringResource(
                R.string.progress_summary,
                (fraction * 100).roundToInt(),
                timeLeftText(remaining)
            ),
            style = MaterialTheme.typography.bodySmall
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

/**
 * How much of a page is left, as a shape the UI can put words to. Split from the
 * wording so the arithmetic stays pure and unit-testable while the phrasing
 * lives in string resources and gets translated — see TimeLeftFormatTest.
 */
internal sealed interface TimeLeft {
    data object AlmostDone : TimeLeft
    data object UnderAMinute : TimeLeft
    data class Minutes(val minutes: Int) : TimeLeft
    data class Hours(val hours: Int, val minutes: Int) : TimeLeft
}

/** A rough readout, so no seconds. */
internal fun timeLeftOf(ms: Long): TimeLeft {
    if (ms <= 0L) return TimeLeft.AlmostDone
    if (ms < 60_000L) return TimeLeft.UnderAMinute
    val minutes = (ms / 60_000L).toInt()
    if (minutes < 60) return TimeLeft.Minutes(minutes)
    return TimeLeft.Hours(minutes / 60, minutes % 60)
}

/** "18 min left", "1 h 5 min left", in the user's language. */
@Composable
private fun timeLeftText(ms: Long): String = when (val left = timeLeftOf(ms)) {
    TimeLeft.AlmostDone -> stringResource(R.string.time_left_almost_done)
    TimeLeft.UnderAMinute -> stringResource(R.string.time_left_under_a_minute)
    is TimeLeft.Minutes -> stringResource(R.string.time_left_minutes, left.minutes)
    is TimeLeft.Hours ->
        if (left.minutes == 0) stringResource(R.string.time_left_hours, left.hours)
        else stringResource(R.string.time_left_hours_minutes, left.hours, left.minutes)
}
