package com.stash.feature.settings.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.diagnostics.DiagnosticsBundleBuilder
import com.stash.core.data.diagnostics.DiagnosticsBundleBuilder.DiagnosticsBundle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Thin ViewModel over [DiagnosticsBundleBuilder]: builds the (already redacted)
 * diagnostics bundle off the main thread on entry, exposing the text + the
 * shareable bundle for the preview screen. [rebuild] re-runs the assembly so the
 * Retry button can recover from a transient failure (e.g. a locked DB).
 */
@HiltViewModel
class DiagnosticsPreviewViewModel @Inject constructor(
    private val builder: DiagnosticsBundleBuilder,
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val text: String = "",
        val bundle: DiagnosticsBundle? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { rebuild() }

    fun rebuild() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { builder.build() } }
            result.onSuccess { b -> _state.update { State(loading = false, text = b.text, bundle = b) } }
                .onFailure { e -> _state.update { State(loading = false, error = e.message ?: "Failed to build diagnostics") } }
        }
    }
}
