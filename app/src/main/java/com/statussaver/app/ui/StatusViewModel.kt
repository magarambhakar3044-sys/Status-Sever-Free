package com.statussaver.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.statussaver.app.data.MediaSaver
import com.statussaver.app.data.SafUriHelper
import com.statussaver.app.data.StatusRepository
import com.statussaver.app.model.SourceFilter
import com.statussaver.app.model.StatusMedia
import com.statussaver.app.model.StatusMediaType
import com.statussaver.app.model.StatusSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val requiresSafOnboarding: Boolean = false,
    val grantedSources: Set<StatusSource> = emptySet(),
    val installedSources: Set<StatusSource> = emptySet(),
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val allStatuses: List<StatusMedia> = emptyList(),
    val activeViewerMediaId: String? = null
) {
    private val filteredStatuses: List<StatusMedia>
        get() = when (sourceFilter) {
            SourceFilter.ALL -> allStatuses
            SourceFilter.WHATSAPP -> allStatuses.filter { it.source == StatusSource.WHATSAPP }
            SourceFilter.WHATSAPP_BUSINESS -> allStatuses.filter { it.source == StatusSource.WHATSAPP_BUSINESS }
        }

    val images: List<StatusMedia>
        get() = filteredStatuses.filter { it.type == StatusMediaType.IMAGE }

    val videos: List<StatusMedia>
        get() = filteredStatuses.filter { it.type == StatusMediaType.VIDEO }
}

sealed interface StatusUiEvent {
    data class ShowMessage(val message: String) : StatusUiEvent
    data class LaunchShareIntent(val chooserIntent: Intent) : StatusUiEvent
}

class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val safUriHelper = SafUriHelper(application)
    private val repository = StatusRepository(application, safUriHelper)
    private val mediaSaver = MediaSaver(application)

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<StatusUiEvent>()
    val events: SharedFlow<StatusUiEvent> = _events.asSharedFlow()

    init {
        refreshPermissionsAndStatuses()
    }

    fun initialUriFor(source: StatusSource): Uri = safUriHelper.buildInitialStatusesUri(source)

    fun onTreeUriGranted(source: StatusSource, treeUri: Uri?) {
        if (treeUri == null) return
        val persisted = safUriHelper.persistTreeUri(source, treeUri)
        viewModelScope.launch {
            if (persisted) {
                _events.emit(StatusUiEvent.ShowMessage("${source.displayName} folder connected"))
                refreshPermissionsAndStatuses()
            } else {
                _events.emit(
                    StatusUiEvent.ShowMessage(
                        "Could not access ${source.displayName} .Statuses folder. Select the .Statuses folder and tap Allow."
                    )
                )
            }
        }
    }

    fun revokeSource(source: StatusSource) {
        safUriHelper.revokeTreeUri(source)
        refreshPermissionsAndStatuses()
    }

    fun refreshPermissionsAndStatuses(isPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            val granted = StatusSource.entries
                .filter { safUriHelper.getPersistedTreeUri(it) != null }
                .toSet()

            val installed = StatusSource.entries
                .filter { safUriHelper.isWhatsAppInstalled(it) }
                .toSet()

            val needsOnboarding = safUriHelper.requiresSaf() && granted.isEmpty()

            _uiState.update {
                it.copy(
                    isLoading = !isPullToRefresh && !needsOnboarding,
                    isRefreshing = isPullToRefresh,
                    requiresSafOnboarding = needsOnboarding,
                    grantedSources = granted,
                    installedSources = installed
                )
            }

            if (needsOnboarding) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, allStatuses = emptyList()) }
                return@launch
            }

            val items = repository.loadRecentStatuses()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    allStatuses = items
                )
            }
        }
    }

    fun setSourceFilter(filter: SourceFilter) {
        _uiState.update { it.copy(sourceFilter = filter) }
    }

    fun openViewer(media: StatusMedia) {
        _uiState.update { it.copy(activeViewerMediaId = media.id) }
    }

    fun closeViewer() {
        _uiState.update { it.copy(activeViewerMediaId = null) }
    }

    fun saveStatus(media: StatusMedia) {
        viewModelScope.launch {
            mediaSaver.saveToPublicPictures(media)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            allStatuses = state.allStatuses.map { item ->
                                if (item.id == media.id) item.copy(isSaved = true) else item
                            }
                        )
                    }
                    _events.emit(StatusUiEvent.ShowMessage("Saved to Pictures/StatusSaver"))
                }
                .onFailure { error ->
                    _events.emit(
                        StatusUiEvent.ShowMessage(
                            error.localizedMessage ?: "Failed to save status"
                        )
                    )
                }
        }
    }

    fun shareStatus(media: StatusMedia) {
        viewModelScope.launch {
            mediaSaver.shareStatus(media)
                .onSuccess { intent ->
                    _events.emit(StatusUiEvent.LaunchShareIntent(intent))
                }
                .onFailure { error ->
                    _events.emit(
                        StatusUiEvent.ShowMessage(
                            error.localizedMessage ?: "Unable to share status"
                        )
                    )
                }
        }
    }
}
