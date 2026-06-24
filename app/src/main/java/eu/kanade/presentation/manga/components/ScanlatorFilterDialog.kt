package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.kanade.domain.manga.model.ScanlatorFilter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private data class ScanlatorUiModel(val scanlator: String?, val excluded: Boolean)

@Composable
fun ScanlatorFilterDialog(
    availableScanlators: Set<String>,
    scanlatorFilter: List<ScanlatorFilter>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<ScanlatorFilter>) -> Unit,
) {
    val filteredScanlators = remember(scanlatorFilter) {
        scanlatorFilter.map { it.scanlator }.toSet()
    }
    val nonFilteredSorted = remember(availableScanlators, filteredScanlators) {
        availableScanlators
            .filterNot { it in filteredScanlators }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    val items = remember(scanlatorFilter, nonFilteredSorted) {
        val filtered = scanlatorFilter
            .sortedBy { it.priority }
            .map { ScanlatorUiModel(it.scanlator, it.excluded) }
        val nonFiltered = nonFilteredSorted.map { ScanlatorUiModel(it, false) }
        (filtered + nonFiltered).toMutableStateList()
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        items.apply { add(to.index, removeAt(from.index)) }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.filter_scanlators)) },
        text = textFunc@{
            if (items.isEmpty()) {
                Text(text = stringResource(MR.strings.no_scanlators_found))
                return@textFunc
            }
            Box {
                ScrollbarLazyColumn(state = lazyListState) {
                    items(items.size, key = { items[it].scanlator ?: "(unknown)" }) { index ->
                        val item = items[index]
                        ReorderableItem(reorderableState, key = item.scanlator ?: "(unknown)") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.padding.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(MaterialTheme.padding.small)
                                        .draggableHandle(),
                                )
                                Text(
                                    text = item.scanlator ?: stringResource(MR.strings.unknown_scanlator),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp),
                                )
                                IconButton(
                                    onClick = {
                                        items[index] = item.copy(excluded = !item.excluded)
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (item.excluded) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = null,
                                    )
                                }
                            }
                        }
                    }
                }
                if (lazyListState.canScrollBackward) {
                    HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                }
                if (lazyListState.canScrollForward) {
                    HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = true,
        ),
        confirmButton = {
            if (items.isEmpty()) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            } else {
                FlowRow {
                    TextButton(onClick = {
                        items.forEachIndexed { index, item -> items[index] = item.copy(excluded = true) }
                    }) {
                        Text(text = stringResource(MR.strings.action_select_all))
                    }
                    TextButton(onClick = {
                        items.forEachIndexed { index, item -> items[index] = item.copy(excluded = false) }
                    }) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            val filters = items.mapIndexed { index, item ->
                                ScanlatorFilter(
                                    scanlator = item.scanlator,
                                    priority = index,
                                    excluded = item.excluded,
                                )
                            }
                            onConfirm(filters)
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            }
        },
    )
}
