package dev.jdtech.jellyfin.presentation.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState

/**
 * Hosts the snackbar that surfaces [DownloaderState.errorText].
 *
 * The error text is set when a download could not be started (for example because the storage
 * location is unavailable or there is not enough free space) and is cleared on the next attempt.
 * Retrying a failed download therefore shows the message again if it fails the same way.
 */
@Composable
fun DownloadErrorSnackbar(
    downloaderState: DownloaderState,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = downloaderState.errorText

    if (errorText != null) {
        val message = errorText.asString()
        LaunchedEffect(errorText) { snackbarHostState.showSnackbar(message) }
    }

    SnackbarHost(hostState = snackbarHostState, modifier = modifier)
}
