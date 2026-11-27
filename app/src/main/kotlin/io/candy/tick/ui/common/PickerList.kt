package io.candy.tick.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

internal const val PICKER_SEARCH_THRESHOLD = 15

/** On short windows the search field scrolls with the results, leaving room for the keyboard. */
@Composable
internal fun PickerList(
    state: LazyListState,
    searchable: Boolean,
    query: String,
    label: String,
    onQueryChange: (String) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val scrollSearch = LocalWindowInfo.current.containerDpSize.height < 480.dp
    val searchField: @Composable () -> Unit = {
        PickerSearchField(query, label) {
            onQueryChange(it)
            state.requestScrollToItem(0)
        }
    }
    Column {
        if (searchable && !scrollSearch) searchField()
        LazyColumn(state = state, modifier = Modifier.weight(1f, fill = false)) {
            if (searchable && scrollSearch) item(key = 0) { searchField() }
            content()
        }
    }
}

@Composable
private fun PickerSearchField(
    query: String,
    label: String,
    onQueryChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    CandyTextField(
        value = query,
        onValueChange = { if (it != query) onQueryChange(it) },
        maxLength = InputLimits.SEARCH,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        trailingIcon = if (query.isEmpty()) null else {
            { TextFieldActionButton(onClick = clearTextFieldAction(focus) { onQueryChange("") }) }
        },
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            // 搜索框下方 8dp：平衡标题→搜索框 vs 搜索框→首行——上方有 WindowBottomSheet
            // 库 insideMargin + 标题 40dp 触控槽的文字下空隙（选择器专用），叠加后视觉偏大
            .padding(bottom = 8.dp)
            .focusRequester(focus),
    )
}
