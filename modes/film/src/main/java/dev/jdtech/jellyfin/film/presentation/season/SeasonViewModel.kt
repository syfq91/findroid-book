package dev.jdtech.jellyfin.film.presentation.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber

@HiltViewModel
class SeasonViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloads: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(SeasonState())
    val state = _state.asStateFlow()

    lateinit var seasonId: UUID

    fun loadSeason(seasonId: UUID) {
        this.seasonId = seasonId
        viewModelScope.launch {
            try {
                val season = repository.getSeason(seasonId)
                val episodes =
                    repository.getEpisodes(
                        seriesId = season.seriesId,
                        seasonId = seasonId,
                        fields = listOf(ItemFields.OVERVIEW),
                    )
                val canDelete = runCatching { repository.canDeleteMedia() }.getOrDefault(false)
                _state.emit(
                    _state.value.copy(
                        season = season,
                        episodes = episodes,
                        canDeleteFromServer = canDelete,
                    )
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    fun onAction(action: SeasonAction) {
        when (action) {
            is SeasonAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(seasonId)
                    loadSeason(seasonId)
                }
            }
            is SeasonAction.DeleteFromServer -> deleteFromServer()
            else -> Unit
        }
    }

    private fun deleteFromServer() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isDeletingFromServer = true, deleteServerError = null))
            try {
                repository.deleteServerItem(seasonId)
                _state.value.season?.let { item ->
                    item.sources
                        .firstOrNull {
                            it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download")
                        }
                        ?.let { source ->
                            runCatching { downloads.deleteItem(item, source) }
                                .onFailure { Timber.w(it, "Failed to remove local download") }
                        }
                }
                _state.emit(
                    _state.value.copy(isDeletingFromServer = false, deletedFromServer = true)
                )
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isDeletingFromServer = false, deleteServerError = e))
            }
        }
    }
}
