# Proposal: Add a button to delete media from the Jellyfin server

No code has been changed. This is a design/implementation proposal.

This revision resolves the UI review issues found against the first draft: the duplicate trash
icon, the claim that `ErrorDialog` is already shown on detail screens, action-row crowding,
success feedback after navigation, in-flight/orphaned downloads, cascading deletion copy, and the
per-load permission lookup.

## Goal

Add a user-facing, permission-gated button that permanently deletes a media item from the Jellyfin
server, with a confirmation dialog and safe navigation/error handling.

## Scope

- **Phone app detail screens**: Movie, Episode, Season, Show, Book.
- **TV app detail screens**: Movie, Show (second phase).
- **Data layer**: repository contract + online/offline implementations.
- **UI layer**: shared button + confirmation dialog + shared snackbar host + state-driven
  navigation.

## Resolved design issues

| Issue | Resolution |
| --- | --- |
| Server delete and local download delete both used `ic_trash` | Add a distinct `ic_delete_forever` vector for the server action; keep `ic_trash` for the local download. |
| `ErrorDialog` is not rendered by Movie/Episode/Show/Season, and it is a stack-trace dialog | Do not reuse `ErrorDialog`. Add a dedicated `deleteServerError` state field and show it in the existing snackbar host. Keep `state.error` for load failures. |
| Action row has no wrapping and already holds six buttons | Put the server-delete button last and verify the smallest supported width. Worst case on a 320 dp screen is five 40 dp secondary icons (download/delete-download + server delete) ≈ 232 dp inside a ≈ 272 dp content width, so it fits; check it in the smallest preview. |
| No success feedback; back navigation is the only signal | Show a `deleted_from_server` Toast immediately before `navigateBack()`. A Toast survives the pop; a detail-screen snackbar would not. |
| In-flight or completed local downloads are not handled | Disable server deletion while a download is in progress; on server-delete success, remove a completed local download through the existing `Downloader.deleteItem` (best effort, logged on failure). |
| Deleting a show/season cascades server-side without warning | Use a container-specific confirmation message for Show/Season. |
| `getCurrentUser()` is called on every detail load | Cache `canDeleteMedia()` per `userId` in the repository; a failed lookup returns `false` (safe: button hidden). |

## Backend capability

Jellyfin supports item deletion via `DELETE /Items/{itemId}`. The bundled SDK already exposes it:

- `JellyfinApi.libraryApi.deleteItem(itemId: UUID): Response<Unit>`
  (verified in `jellyfin-api-jvm 1.9.4`)

Permission comes from the current user’s policy:

- `UserDto.policy.enableContentDeletion` (and `isAdministrator`)
- fetched through `JellyfinApi.userApi.getCurrentUser()`

## Data layer changes

### `JellyfinRepository`

Add two methods:

```kotlin
suspend fun deleteServerItem(itemId: UUID)
suspend fun canDeleteMedia(): Boolean
```

### `JellyfinRepositoryImpl`

- `deleteServerItem`:
  ```kotlin
  withContext(Dispatchers.IO) {
      jellyfinApi.libraryApi.deleteItem(itemId)
  }
  ```
- `canDeleteMedia`, cached per user id:
  ```kotlin
  suspend fun canDeleteMedia(): Boolean {
      val userId = getUserId()
      canDeleteCache[userId]?.let { return it }
      return try {
          val policy = jellyfinApi.userApi.getCurrentUser().content.policy
          val allowed = policy?.isAdministrator == true || policy?.enableContentDeletion == true
          canDeleteCache[userId] = allowed
          allowed
      } catch (_: Exception) {
          false
      }
  }
  ```
  The cache is in-memory per repository (server) instance and keyed by user id. A server-side
  policy change requires re-login or an app restart; that is accepted.

### `JellyfinRepositoryOfflineImpl`

- `deleteServerItem` throws `IOException("Deleting from the server is unavailable in offline mode")`.
- `canDeleteMedia` returns `false` (and is not cached).

This makes the feature automatically unavailable in offline mode.

## ViewModel changes

Add to `MovieState`, `EpisodeState`, `ShowState`, `SeasonState`, and `BookState`:

```kotlin
val canDeleteFromServer: Boolean = false
val isDeletingFromServer: Boolean = false
val deleteServerError: Exception? = null
val deletedFromServer: Boolean = false
```

`deleteServerError` is deliberately separate from the existing `error` field so a failed deletion
does not get mixed up with a failed screen load.

Add a `DeleteFromServer` action to each corresponding action sealed interface.

Inject `Downloader` into the item ViewModels so local cleanup can be completed before the screen
navigates away (`modes/film` and `modes/book` already depend on `core`, and `Downloader` is
provided by Hilt).

ViewModel handling pattern:

```kotlin
is MovieAction.DeleteFromServer -> {
    viewModelScope.launch {
        _state.update {
            it.copy(isDeletingFromServer = true, deleteServerError = null)
        }
        try {
            repository.deleteServerItem(movieId)

            // Best-effort: remove a completed local download so it does not become orphaned.
            _state.value.movie?.let { item ->
                item.sources
                    .firstOrNull {
                        it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download")
                    }
                    ?.let { source ->
                        runCatching { downloader.deleteItem(item, source) }
                            .onFailure { Timber.w(it, "Failed to remove local download") }
                    }
            }

            _state.update {
                it.copy(isDeletingFromServer = false, deletedFromServer = true)
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(isDeletingFromServer = false, deleteServerError = e)
            }
        }
    }
}
```

During load, populate permission:

```kotlin
val canDelete = runCatching { repository.canDeleteMedia() }.getOrDefault(false)
```

and include it in the emitted state. This runs once per detail load, but the repository cache makes
repeated loads cheap.

In-flight downloads are not deleted here: the UI disables the action while
`downloaderState.isDownloading` is true, so a completed source is the only case this cleanup sees.
A download that finishes between the last UI update and the server delete is handled by the same
best-effort cleanup.

## UI changes

### Shared snackbar host

The download UI now has `DownloadErrorSnackbar`, which owns its own `SnackbarHostState`. A second
host for deletion errors would overlap. Instead:

- Own one `SnackbarHostState` per detail screen.
- Extract the host rendering into a small shared `MessageSnackbarHost` composable.
- Replace the body of `DownloadErrorSnackbar` with an effect that calls `showSnackbar` on the
  shared state (or fold both messages into one `SnackbarEffect`/`MessageSnackbar` composable).
- Add a `deleteServerError` effect using the same host, displaying
  `exception.localizedMessage ?: stringResource(CoreR.string.unknown_error)`.

This also replaces the proposal’s incorrect assumption that `ErrorDialog` is already wired into
the detail screens.

### Phone

1. **`ItemButtonsBar`** and **`BookButtonsBar`**:
   - Add parameters:
     ```kotlin
     canDeleteFromServer: Boolean = false,
     isDeletingFromServer: Boolean = false,
     onDeleteFromServerClick: () -> Unit = {},
     ```
   - Render a `FilledTonalIconButton` using a new `CoreR.drawable.ic_delete_forever` when
     `canDeleteFromServer` is true. Keep the existing `ic_trash` for deleting a local download.
   - Place it last in the action row, after the download/delete-download button.
   - `enabled = !isDeletingFromServer && downloaderState?.isDownloading != true`.
   - Give it a `delete_from_server` content description.
   - Verify the row at the smallest supported width (see the resolved-issues table).

2. **New `DeleteServerItemDialog`** composable, analogous to `DeleteDownloadDialog`:
   - Item copy: “Delete from server” / “This will permanently delete this item from the Jellyfin
     server. Are you sure?”
   - Container copy for Show/Season: “This will permanently delete this show/season and all of its
     episodes from the Jellyfin server. Are you sure?”
   - If the item is downloaded, add a line stating the downloaded copy on this device will also be
     removed.
   - Confirm button styled as destructive (error color), cancel button dismisses.
   - Own the dialog state in the bar (like `DownloadDialogs` already does) so the five screens do
     not each duplicate `rememberSaveable` dialog handling. The bars know whether the item is a
     container, so they can pick the right copy.

3. **Detail screens**:
   - Pass:
     ```kotlin
     canDeleteFromServer = state.canDeleteFromServer,
     isDeletingFromServer = state.isDeletingFromServer,
     onDeleteFromServerClick = { /* dispatch DeleteFromServer */ },
     ```
   - Observe success:
     ```kotlin
     LaunchedEffect(state.deletedFromServer) {
         if (state.deletedFromServer) {
             Toast.makeText(context, CoreR.string.deleted_from_server, Toast.LENGTH_SHORT).show()
             navigateBack()
         }
     }
     ```
   - Observe `state.deleteServerError` and show it in the shared snackbar host.
   - `BookScreen` keeps its inline load-error text; deletion errors go through the snackbar.

### TV

- Add a conditional “Delete from server” button to Movie and Show screens.
- Disable it while `isDeletingFromServer` (TV has no download state to consider).
- The dialog uses the same shared composable.
- Add `navigateBack` handling for `deletedFromServer`.
- Update `NavigationRoot` to pass `goBack()` where needed.
- TV success feedback uses `LaunchedEffect` + `goBack()`; a Toast may be omitted on TV if it does
  not fit the platform’s feedback style.

## Strings

Add to `core/src/main/res/values/strings.xml` (and translations later):

```xml
<string name="delete_from_server">Delete from server</string>
<string name="delete_from_server_message">
    This will permanently delete this item from the Jellyfin server. Are you sure?
</string>
<string name="delete_from_server_container_message">
    This will permanently delete this %1$s and all of its episodes from the Jellyfin server. Are you sure?
</string>
<string name="delete_from_server_downloaded_message">
    The downloaded copy on this device will also be removed.
</string>
<string name="deleted_from_server">Deleted from server</string>
```

Existing `unknown_error` covers failure fallback text.

## Navigation and cleanup

- On success, show the success Toast and navigate back / pop the back stack.
- Completed local downloads are removed before success is reported (best effort; failures are
  logged and do not block the server deletion).
- Server deletion is disabled while a download is in progress, avoiding most in-flight races.
- Existing lists refresh on next navigation.

## Error handling

- On failure, set `deleteServerError`; the shared snackbar shows it. `state.error` remains
  reserved for load failures.
- The button is re-enabled and remains available for retry.
- A failed server delete leaves any local download untouched.

## Permission UX

- Button is only shown when `canDeleteFromServer` is true.
- In offline mode, the repository returns `false`, so the button is hidden.
- Permission is cached per user; a lookup failure hides the button rather than showing a broken
  action.

## Risks

- Destructive operation: confirmation dialog must be clear and not easily dismissed accidentally.
- Deleting shows/seasons can remove many files server-side; use the container-specific copy.
- A download completing between the UI check and the server delete is a narrow race; the
  best-effort local cleanup handles the common case and logs the rest.
- The permission cache can be stale after a server-side policy change until re-login/restart.
- Extra on-screen destructive icon: keep it visually and semantically distinct from the local
  delete.

## Implementation order

1. Data layer: repository methods, offline behavior, permission cache.
2. ViewModels: state fields, `DeleteFromServer` actions, `Downloader` injection, local cleanup.
3. Strings + `ic_delete_forever`.
4. Shared snackbar host refactor (`MessageSnackbarHost` / download-error effect).
5. Phone UI: `DeleteServerButton`/dialog, `ItemButtonsBar`, `BookButtonsBar`.
6. Phone detail screens: Movie and Episode first, then Season, Show, Book.
7. Tests.
8. TV UI.

## Tests

- Repository permission parsing (`administrator` or `enableContentDeletion`) and per-user caching.
- Offline repository: `canDeleteMedia() == false`, `deleteServerItem` throws.
- ViewModel success sets `deletedFromServer` and removes a completed local download; failure sets
  `deleteServerError` and leaves the local download in place.
- Compose: button visibility by permission/offline, disabled while deleting or downloading,
  correct dialog copy for items vs containers, confirm/cancel, success Toast + navigation, error
  snackbar.
- Manual: delete movie/episode/season/show/book; verify server removal, back navigation, downloaded
  copy removal, and the button being hidden for unauthorized/offline users.
