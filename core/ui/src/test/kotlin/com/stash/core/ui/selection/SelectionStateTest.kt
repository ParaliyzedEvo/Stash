package com.stash.core.ui.selection

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionStateTest {
    @Test fun starts_inactive_and_empty() {
        val s = SelectionState()
        assertThat(s.isActive).isFalse()
        assertThat(s.count).isEqualTo(0)
    }
    @Test fun enter_selects_single_and_activates() {
        val s = SelectionState()
        s.enter(7L)
        assertThat(s.isActive).isTrue()
        assertThat(s.isSelected(7L)).isTrue()
        assertThat(s.count).isEqualTo(1)
    }
    @Test fun toggle_adds_then_removes() {
        val s = SelectionState(setOf(1L))
        s.toggle(2L); assertThat(s.selectedIds).containsExactly(1L, 2L)
        s.toggle(1L); assertThat(s.selectedIds).containsExactly(2L)
    }
    @Test fun deselecting_last_track_deactivates() {
        val s = SelectionState(setOf(1L))
        s.toggle(1L)
        assertThat(s.isActive).isFalse()
    }
    @Test fun selectAll_then_clear() {
        val s = SelectionState()
        s.selectAll(listOf(1L, 2L, 3L))
        assertThat(s.count).isEqualTo(3)
        s.clear()
        assertThat(s.isActive).isFalse()
    }
    @Test fun saver_round_trips_the_id_set() {
        val original = SelectionState(setOf(4L, 9L))
        val saved = with(SelectionStateSaver) { SaverScopeStub.save(original) }
        val restored = SelectionStateSaver.restore(saved!!)
        assertThat(restored!!.selectedIds).containsExactly(4L, 9L)
    }
}

private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
