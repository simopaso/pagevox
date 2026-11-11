package fi.paso.pagevox

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val FOCUS_TAP_WINDOW_MS = 500L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddressBar(
    url: String,
    history: List<WebPage>,
    isBookmarked: Boolean,
    onGo: (String) -> Unit,
    onToggleBookmark: () -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue(url, TextRange(url.length))) }
    var focused by remember { mutableStateOf(false) }
    var keepSelectedUntil by remember { mutableStateOf(0L) }
    val focusManager = LocalFocusManager.current
    val text = field.text
    var expanded by remember { mutableStateOf(false) }

    // Keep the field in sync with the page URL, but never while the user is
    // editing — a redirect or title-driven URL update mid-typing used to wipe
    // their input (the field was keyed on url with remember(url)).
    LaunchedEffect(url) {
        if (!focused) field = TextFieldValue(url, TextRange(url.length))
    }

    // Closing the keyboard ends the edit, as it does in Chrome. Back only hides the
    // keyboard: the field would stay focused, and the next tap would move the cursor
    // in it instead of selecting the address. Waits to have seen the keyboard open,
    // since it's still closed at the moment the field gains focus, and because with a
    // hardware keyboard it never opens and the field has to keep working.
    val keyboardOpen = WindowInsets.isImeVisible
    var keyboardWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardOpen) {
        if (keyboardWasOpen && !keyboardOpen && focused) focusManager.clearFocus()
        keyboardWasOpen = keyboardOpen
    }

    // Match history (url or title) against what's typed. Don't suggest when the
    // field still shows the current page's URL untouched (nothing useful to offer).
    val suggestions = remember(text, history) {
        if (text.isBlank() || text == url) emptyList()
        else history.filter {
            (it.url.contains(text, ignoreCase = true) || it.title.contains(text, ignoreCase = true)) &&
                it.url != text
        }.take(6)
    }
    val showMenu = expanded && suggestions.isNotEmpty()

    fun submit(value: String) {
        expanded = false
        // Dropping focus dismisses the keyboard and lets the LaunchedEffect
        // above swap the field to the resolved URL once navigation lands.
        focusManager.clearFocus()
        onGo(value)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                expanded = showMenu,
                onExpandedChange = { },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        // Selecting on focus isn't enough: the tap that gave the field
                        // focus finishes afterwards and puts the cursor where the finger
                        // landed, undoing the selection. That arrives here as the same
                        // text with an empty selection, so turn it back into the whole
                        // address. Timed rather than counted, because it can arrive more
                        // than once; a later tap is someone placing the cursor.
                        val tapAfterFocus = SystemClock.uptimeMillis() < keepSelectedUntil &&
                            it.text == field.text && it.selection.collapsed
                        if (tapAfterFocus) {
                            field = it.copy(selection = TextRange(0, it.text.length))
                        } else {
                            field = it
                            expanded = it.text.isNotBlank()
                        }
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            focused = focus.isFocused
                            if (focus.isFocused) {
                                // When the bar gains focus, select the whole URL so typing
                                // instantly replaces it (Chrome-like behavior).
                                field = field.copy(selection = TextRange(0, field.text.length))
                                keepSelectedUntil = SystemClock.uptimeMillis() + FOCUS_TAP_WINDOW_MS
                            } else {
                                // Abandoning an edit restores the page URL.
                                field = TextFieldValue(url, TextRange(url.length))
                                expanded = false
                            }
                        },
                    singleLine = true,
                    // A pill, as Material 3 Expressive draws its search and
                    // address fields.
                    shape = MaterialTheme.shapes.extraLarge,
                    trailingIcon = {
                        IconButton(onClick = onToggleBookmark) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = stringResource(
                                    if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark
                                )
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(text) })
                )
                ExposedDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { expanded = false }
                ) {
                    suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    if (suggestion.title.isNotBlank() && suggestion.title != suggestion.url) {
                                        Text(
                                            text = suggestion.title,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    Text(
                                        text = suggestion.url,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            onClick = {
                                field = TextFieldValue(suggestion.url, TextRange(suggestion.url.length))
                                submit(suggestion.url)
                            }
                        )
                    }
                }
            }
        }
    }
}
