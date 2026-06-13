package com.stash.feature.library.mixbuilder

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.GenreCatalog
import com.stash.core.data.mix.MixRecipeForm
import com.stash.core.data.mix.MoodTagMap
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MixBuilderUiState(
    val form: MixRecipeForm = MixRecipeForm(),
    val families: List<GenreCatalog.Family> = GenreCatalog.FAMILIES,
    val moods: List<String> = MoodTagMap.ALL_MOODS,
    val isEditing: Boolean = false,
) { val canSave: Boolean get() = form.isValid }

@HiltViewModel
class MixBuilderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recipeDao: StashMixRecipeDao,
    private val musicRepository: MusicRepository,
    private val downloadNetworkPreference: DownloadNetworkPreference,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val recipeId: Long? = savedStateHandle.get<Long>("recipeId")

    private val _uiState = MutableStateFlow(MixBuilderUiState(isEditing = recipeId != null))
    val uiState: StateFlow<MixBuilderUiState> = _uiState.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init { recipeId?.let { loadExisting(it) } }

    private fun loadExisting(id: Long) {
        viewModelScope.launch {
            recipeDao.getById(id)?.let { r ->
                _uiState.value = _uiState.value.copy(form = MixRecipeForm.fromRecipe(r))
            }
        }
    }

    private fun update(block: (MixRecipeForm) -> MixRecipeForm) {
        _uiState.value = _uiState.value.copy(form = block(_uiState.value.form))
    }
    fun setName(name: String) = update { it.copy(name = name) }
    fun toggleGenre(tag: String) = update { it.copy(genreTags = it.genreTags.toggle(tag)) }
    fun toggleMood(key: String) = update { it.copy(moodKeys = it.moodKeys.toggle(key)) }
    fun setEra(startYear: Int?, endYear: Int?) = update { it.copy(eraStartYear = startYear, eraEndYear = endYear) }
    fun setDiscoveryRatio(value: Float) = update { it.copy(discoveryRatio = value) }

    fun save() {
        val form = _uiState.value.form
        if (!form.isValid) return
        viewModelScope.launch {
            val recipe = form.toRecipe(existingId = recipeId)
            val id = if (recipeId != null) { recipeDao.update(recipe); recipeId } else recipeDao.insert(recipe)
            // Fire-and-forget materialization + discovery. The refresh worker
            // materializes the playlist and FILLS the discovery queue, but it
            // does NOT drain it — so we must also kick StashDiscoveryWorker
            // (exactly what "Refresh this mix" does), otherwise a new mix only
            // populates whenever discovery next happens to run (the daily
            // schedule), which is why it "appeared broken" for minutes.
            // Guarded like StashMixRefreshWorker guards its own ArtBackfill
            // enqueue: a not-yet-initialized WorkManager (unit-test JVM) throws,
            // and that must never sink the save the user already committed.
            runCatching {
                StashMixRefreshWorker.enqueueOneTime(context, id)
                StashDiscoveryWorker.enqueueOneTime(context, downloadNetworkPreference.current())
            }
            _saved.tryEmit(Unit)
        }
    }

    private fun <T> Set<T>.toggle(item: T): Set<T> =
        if (contains(item)) this - item else this + item
}
