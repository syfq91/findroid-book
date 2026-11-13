package dev.jdtech.jellyfin.book.presentation.book

import dev.jdtech.jellyfin.models.FindroidBook

data class BookState(
    val book: FindroidBook? = null,
    val isLoading: Boolean = false,
    val error: Exception? = null,
    val canDeleteFromServer: Boolean = false,
    val isDeletingFromServer: Boolean = false,
    val deleteServerError: Exception? = null,
    val deletedFromServer: Boolean = false,
)
