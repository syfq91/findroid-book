package dev.jdtech.jellyfin.book.presentation.book

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
import timber.log.Timber

@HiltViewModel
class BookViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val downloads: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(BookState())
    val state = _state.asStateFlow()

    lateinit var bookId: UUID

    fun loadBook(bookId: UUID) {
        this.bookId = bookId
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))
            try {
                val book = repository.getBook(bookId)
                val canDelete = runCatching { repository.canDeleteMedia() }.getOrDefault(false)
                _state.emit(
                    _state.value.copy(
                        book = book,
                        isLoading = false,
                        error = null,
                        canDeleteFromServer = canDelete,
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load book $bookId")
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    fun onAction(action: BookAction) {
        when (action) {
            is BookAction.MarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsPlayed(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.UnmarkAsPlayed -> {
                viewModelScope.launch {
                    repository.markAsUnplayed(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.MarkAsFavorite -> {
                viewModelScope.launch {
                    repository.markAsFavorite(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.UnmarkAsFavorite -> {
                viewModelScope.launch {
                    repository.unmarkAsFavorite(bookId)
                    loadBook(bookId)
                }
            }
            is BookAction.DeleteFromServer -> deleteFromServer()
            else -> Unit
        }
    }

    private fun deleteFromServer() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isDeletingFromServer = true, deleteServerError = null))
            try {
                repository.deleteServerItem(bookId)
                _state.value.book?.let { item ->
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
                Timber.e(e, "Failed to delete book $bookId from server")
                _state.emit(_state.value.copy(isDeletingFromServer = false, deleteServerError = e))
            }
        }
    }
}
