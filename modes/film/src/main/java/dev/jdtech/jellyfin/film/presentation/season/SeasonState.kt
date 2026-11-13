package dev.jdtech.jellyfin.film.presentation.season

import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSeason

data class SeasonState(
    val season: FindroidSeason? = null,
    val episodes: List<FindroidEpisode> = emptyList(),
    val error: Exception? = null,
    val canDeleteFromServer: Boolean = false,
    val isDeletingFromServer: Boolean = false,
    val deleteServerError: Exception? = null,
    val deletedFromServer: Boolean = false,
)
