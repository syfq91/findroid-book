package dev.jdtech.jellyfin.presentation.components

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState

/** Renders the snackbars queued on [hostState]. */
@Composable
fun MessageSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier)
}

/**
 * Shows [DownloaderState.errorText] in the shared snackbar host.
 *
 * The error text is set when a download could not be started (for example because the storage
 * location is unavailable or there is not enough free space) and is cleared on the next attempt.
 * Retrying a failed download therefore shows the message again if it fails the same way.
 */
@Composable
fun DownloadErrorEffect(downloaderState: DownloaderState, hostState: SnackbarHostState) {
    val errorText = downloaderState.errorText

    if (errorText != null) {
        val message = errorText.asString()
        LaunchedEffect(errorText) { hostState.showSnackbar(message) }
    }
}

/** Shows a failed server-side deletion in the shared snackbar host. */
@Composable
fun DeleteServerErrorEffect(error: Exception?, hostState: SnackbarHostState) {
    if (error != null) {
        val message = error.localizedMessage ?: stringResource(CoreR.string.unknown_error)
        LaunchedEffect(error) { hostState.showSnackbar(message) }
    }
}
