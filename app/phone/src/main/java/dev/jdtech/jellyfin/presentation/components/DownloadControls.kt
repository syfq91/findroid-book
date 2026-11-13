package dev.jdtech.jellyfin.presentation.components

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.StorageSelectionDialog
import java.io.File
import kotlin.math.roundToInt

/**
 * State shared by [DownloadButton] and [DownloadDialogs].
 *
 * They render at different positions: the button sits inside a button row, while the dialogs render
 * as separate windows. They must agree on the chosen storage index and which dialog is open, so
 * that state lives here and is remembered once per bar.
 */
@Stable
class DownloadControlsState {
    var storageSelectionDialogOpen by mutableStateOf(false)
    var deleteDownloadDialogOpen by mutableStateOf(false)
    var selectedStorageIndex by mutableIntStateOf(0)
    var storageLocations: Array<File> = emptyArray()
}

/** Creates the [DownloadControlsState] shared by the download button and dialogs. */
@Composable
fun rememberDownloadControlsState(): DownloadControlsState {
    return remember { DownloadControlsState() }
}

/**
 * The download / delete action button.
 *
 * While a download is running the button becomes a circular progress indicator with the current
 * percentage in the middle, and tapping it cancels the download. Once the item is downloaded it
 * shows a delete (trash) button, and otherwise a download button.
 *
 * Starting a download either opens the storage-selection dialog (when more than one storage is
 * available) or downloads directly to the default storage.
 */
@Composable
fun DownloadButton(
    item: FindroidItem,
    state: DownloadControlsState,
    downloaderState: DownloaderState?,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    if (downloaderState != null && downloaderState.isDownloading) {
        val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        val animatedProgress by
            animateFloatAsState(
                targetValue = downloaderState.progress,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            )
        FilledTonalIconButton(
            onClick = onDownloadCancelClick,
            modifier = modifier,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.24f),
                    strokeWidth = 3.dp,
                )
                Text(
                    text = "${(animatedProgress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    } else if (item.isDownloaded()) {
        FilledTonalIconButton(
            onClick = { state.deleteDownloadDialogOpen = true },
            modifier = modifier,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_cloud_done),
                contentDescription = null,
            )
        }
    } else if (item.canDownload) {
        FilledTonalIconButton(
            onClick = {
                state.storageLocations = context.getExternalFilesDirs(null)
                if (state.storageLocations.size > 1) {
                    state.storageSelectionDialogOpen = true
                } else {
                    state.selectedStorageIndex = 0
                    onDownloadClick(0)
                }
            },
            modifier = modifier,
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_cloud_download),
                contentDescription = null,
            )
        }
    }
}

/**
 * The storage-selection and delete-download dialogs, driven by [state]. These render as separate
 * dialog windows, so their position in the composition tree does not affect layout.
 */
@Composable
fun DownloadDialogs(
    state: DownloadControlsState,
    onDownloadClick: (storageIndex: Int) -> Unit,
    onDownloadDeleteClick: () -> Unit,
) {
    if (state.storageSelectionDialogOpen) {
        val resources = LocalResources.current
        val locations =
            remember(resources, state.storageLocations) {
                state.storageLocations.map { dir ->
                    val locationStringRes =
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    val locationString = resources.getString(locationStringRes)

                    val stat = StatFs(dir.path)
                    val availableMegaBytes = stat.availableBytes.div(1000000)
                    resources.getString(
                        CoreR.string.storage_name,
                        locationString,
                        availableMegaBytes,
                    )
                }
            }
        StorageSelectionDialog(
            storageLocations = locations,
            onSelect = { storageIndex ->
                state.selectedStorageIndex = storageIndex
                onDownloadClick(storageIndex)
                state.storageSelectionDialogOpen = false
            },
            onDismiss = { state.storageSelectionDialogOpen = false },
        )
    }
    if (state.deleteDownloadDialogOpen) {
        DeleteDownloadDialog(
            onDelete = {
                onDownloadDeleteClick()
                state.deleteDownloadDialogOpen = false
            },
            onDismiss = { state.deleteDownloadDialogOpen = false },
        )
    }
}
