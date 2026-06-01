package com.stash.core.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Multi-select state for one track-list screen. `isActive` is derived from a non-empty selection. */
class SelectionState(initial: Set<Long> = emptySet()) {
    var selectedIds by mutableStateOf(initial)
        private set
    val isActive: Boolean get() = selectedIds.isNotEmpty()
    val count: Int get() = selectedIds.size
    fun isSelected(id: Long): Boolean = id in selectedIds
    fun enter(id: Long) { selectedIds = selectedIds + id }
    fun toggle(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun selectAll(ids: Collection<Long>) { selectedIds = ids.toSet() }
    fun clear() { selectedIds = emptySet() }
}

/** Survives rotation / process death by persisting the id set. */
val SelectionStateSaver: Saver<SelectionState, List<Long>> = Saver(
    save = { it.selectedIds.toList() },
    restore = { SelectionState(it.toSet()) },
)

@Composable
fun rememberSelectionState(): SelectionState =
    rememberSaveable(saver = SelectionStateSaver) { SelectionState() }
