package dev.jdtech.jellyfin.book.reader

import android.content.Context
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.DownloadResult
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.mediatype.MediaType
import timber.log.Timber

/** The resolved book file plus the reading position and runtime the server has for it, in ticks. */
data class PreparedBook(
    val file: File,
    val serverPositionTicks: Long,
    val serverRuntimeTicks: Long = 0L,
    val formatHints: FormatHints? = null,
)

/** Resolves a book to a local file, reusing and revalidating a persistent reader copy. */
class BookFilePreparer(
    private val context: Context,
    private val jellyfinRepository: JellyfinRepository,
    private val bookId: UUID,
    private val containerHint: String?,
) {
    suspend fun prepare(): PreparedBook =
        withContext(Dispatchers.IO) {
            var container = containerHint
            var serverPositionTicks = 0L
            var serverRuntimeTicks = 0L
            try {
                val book = jellyfinRepository.getBook(bookId)
                serverPositionTicks = book.playbackPositionTicks
                serverRuntimeTicks = book.runtimeTicks
                if (container.isNullOrBlank()) {
                    container = book.container
                }
                val localSource = book.sources.firstOrNull { it.type == FindroidSourceType.LOCAL }
                if (localSource != null) {
                    val localFile = File(localSource.path)
                    if (localFile.exists() && localFile.length() > 0) {
                        Timber.d("Using downloaded local book file: ${localFile.absolutePath}")
                        val detected = detectBookFormat(localFile, container)
                        val ext =
                            container?.lowercase()?.removePrefix(".")?.takeIf {
                                it in BOOK_EXTENSIONS
                            } ?: detected
                        val hints = buildFormatHints(ext)
                        return@withContext PreparedBook(
                            file = localFile,
                            serverPositionTicks = serverPositionTicks,
                            serverRuntimeTicks = serverRuntimeTicks,
                            formatHints = hints,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not fetch book details or local source")
            }

            // Keep reader copies in filesDir so the OS does not evict them as cache, and prune
            // entries that have not been opened for a while.
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            pruneBookCache(booksDir)
            val existing = findCachedBook(booksDir)
            if (existing != null) {
                val resolvedFile = normalizeCachedBookExtension(existing, booksDir, container)
                val revalidation = revalidateCachedBook(resolvedFile, booksDir)
                val preparedFile =
                    if (revalidation.notModified) {
                        touchCachedBook(resolvedFile, booksDir)
                        resolvedFile
                    } else {
                        storeBookDownload(
                            result = revalidation,
                            booksDir = booksDir,
                            existing = resolvedFile,
                            container = container,
                        )
                    }
                val finalExt =
                    preparedFile.extension.lowercase().ifBlank {
                        detectBookFormat(preparedFile, container)
                    }
                val hints = buildFormatHints(finalExt)
                return@withContext PreparedBook(
                    file = preparedFile,
                    serverPositionTicks = serverPositionTicks,
                    serverRuntimeTicks = serverRuntimeTicks,
                    formatHints = hints,
                )
            }

            val result = jellyfinRepository.openDownloadStream(bookId)
            val resolvedFile =
                storeBookDownload(
                    result = result,
                    booksDir = booksDir,
                    existing = null,
                    container = container,
                )
            val finalExt =
                resolvedFile.extension.lowercase().ifBlank {
                    detectBookFormat(resolvedFile, container)
                }
            val hints = buildFormatHints(finalExt)
            PreparedBook(
                file = resolvedFile,
                serverPositionTicks = serverPositionTicks,
                serverRuntimeTicks = serverRuntimeTicks,
                formatHints = hints,
            )
        }

    private fun findCachedBook(booksDir: File): File? =
        booksDir
            .listFiles { _, name ->
                name.startsWith("$bookId.") && !name.endsWith(".tmp") && !name.endsWith(".meta")
            }
            ?.firstOrNull { it.length() > 0 }

    private fun normalizeCachedBookExtension(
        file: File,
        booksDir: File,
        container: String?,
    ): File {
        val detected = detectBookFormat(file, container)
        val targetExt =
            container?.lowercase()?.removePrefix(".")?.takeIf { it in BOOK_EXTENSIONS } ?: detected
        return if (file.extension.lowercase() != targetExt && targetExt in BOOK_EXTENSIONS) {
            safeMoveOrCopy(file, File(booksDir, "$bookId.$targetExt"))
        } else {
            file
        }
    }

    /**
     * Revalidates a cached copy with conditional headers. When the server does not provide
     * validators yet, compare the reported size instead of downloading the whole book again. Any
     * request failure falls back to the cached copy so offline reading keeps working.
     */
    private suspend fun revalidateCachedBook(file: File, booksDir: File): DownloadResult {
        val validators = readBookValidators(booksDir)
        return try {
            if (validators != null) {
                jellyfinRepository.openDownloadStream(
                    bookId,
                    validators.eTag,
                    validators.lastModified,
                )
            } else {
                val remoteSize = jellyfinRepository.getDownloadSize(bookId)
                if (remoteSize == null || remoteSize == file.length()) {
                    DownloadResult(
                        stream = null,
                        eTag = null,
                        lastModified = null,
                        contentLength = file.length(),
                        notModified = true,
                    )
                } else {
                    jellyfinRepository.openDownloadStream(bookId)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not revalidate the cached book; using the cached copy")
            DownloadResult(
                stream = null,
                eTag = null,
                lastModified = null,
                contentLength = file.length(),
                notModified = true,
            )
        }
    }

    private fun storeBookDownload(
        result: DownloadResult,
        booksDir: File,
        existing: File?,
        container: String?,
    ): File {
        val tempFile = File(booksDir, "$bookId.tmp")
        try {
            val stream = result.stream ?: throw IOException("Server did not return the book file")
            stream.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }

        val detected = detectBookFormat(tempFile, container)
        val finalExt =
            container?.lowercase()?.removePrefix(".")?.takeIf { it in BOOK_EXTENSIONS } ?: detected
        val resolvedFile = safeMoveOrCopy(tempFile, File(booksDir, "$bookId.$finalExt"))
        if (existing != null && existing != resolvedFile && existing.exists()) {
            existing.delete()
        }
        writeBookValidators(booksDir, result)
        resolvedFile.setLastModified(System.currentTimeMillis())
        return resolvedFile
    }

    private fun touchCachedBook(file: File, booksDir: File) {
        val now = System.currentTimeMillis()
        file.setLastModified(now)
        File(booksDir, "$bookId.meta").takeIf { it.exists() }?.setLastModified(now)
    }

    private fun readBookValidators(booksDir: File): BookCacheValidators? {
        val metaFile = File(booksDir, "$bookId.meta")
        if (!metaFile.exists()) {
            return null
        }
        return try {
            val json = JSONObject(metaFile.readText())
            BookCacheValidators(
                eTag = json.optString("eTag").takeIf { it.isNotBlank() },
                lastModified = json.optString("lastModified").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not read cached book validators")
            null
        }
    }

    private fun writeBookValidators(booksDir: File, result: DownloadResult) {
        val metaFile = File(booksDir, "$bookId.meta")
        if (result.eTag.isNullOrBlank() && result.lastModified.isNullOrBlank()) {
            metaFile.delete()
            return
        }
        val json =
            JSONObject().apply {
                result.eTag?.let { put("eTag", it) }
                result.lastModified?.let { put("lastModified", it) }
            }
        metaFile.writeText(json.toString())
    }

    private fun pruneBookCache(booksDir: File) {
        val now = System.currentTimeMillis()
        val cutoff = now - BOOK_CACHE_MAX_AGE_MS
        val tempCutoff = now - BOOK_TEMP_MAX_AGE_MS
        booksDir.listFiles()?.forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            val abandonedTemp = file.name.endsWith(".tmp") && file.lastModified() < tempCutoff
            if (file.lastModified() < cutoff || abandonedTemp) {
                file.delete()
            }
        }
    }

    private data class BookCacheValidators(val eTag: String?, val lastModified: String?)

    companion object {
        private const val BOOK_CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val BOOK_TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val BOOK_EXTENSIONS = setOf("cbz", "cbr", "epub", "pdf")

        fun detectBookFormat(file: File, containerHint: String? = null): String {
            val header = ByteArray(7)
            try {
                FileInputStream(file).use { input -> input.read(header) }
            } catch (_: Exception) {}

            if (
                header.size >= 4 &&
                    header[0] == 0x25.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x44.toByte() &&
                    header[3] == 0x46.toByte()
            ) {
                return "pdf"
            }

            if (
                header.size >= 4 &&
                    header[0] == 0x52.toByte() &&
                    header[1] == 0x61.toByte() &&
                    header[2] == 0x72.toByte() &&
                    header[3] == 0x21.toByte()
            ) {
                return "cbr"
            }

            val comicImageExtensions =
                setOf(
                    "jpg",
                    "jpeg",
                    "png",
                    "webp",
                    "avif",
                    "gif",
                    "bmp",
                    "dib",
                    "tif",
                    "tiff",
                    "jxl",
                )

            // Try opening as ZIP. Use UTF-8 first, and fall back to ISO-8859-1 for non-UTF8 entry
            // encodings (e.g. Shift-JIS manga).
            val zipFile =
                try {
                    ZipFile(file, StandardCharsets.UTF_8)
                } catch (_: Exception) {
                    try {
                        ZipFile(file, StandardCharsets.ISO_8859_1)
                    } catch (_: Exception) {
                        null
                    }
                }

            zipFile?.use { zip ->
                // 1. Strict EPUB verification:
                // Standard EPUB must contain META-INF/container.xml
                if (zip.getEntry("META-INF/container.xml") != null) {
                    return "epub"
                }
                // If it has a mimetype entry, check if its content is application/epub+zip
                val mimetypeEntry = zip.getEntry("mimetype")
                if (mimetypeEntry != null) {
                    try {
                        val mimeContent =
                            zip.getInputStream(mimetypeEntry).use {
                                it.bufferedReader(StandardCharsets.US_ASCII).readText().trim()
                            }
                        if (mimeContent.startsWith("application/epub+zip", ignoreCase = true)) {
                            return "epub"
                        }
                        if (
                            mimeContent.startsWith(
                                "application/vnd.comicbook+zip",
                                ignoreCase = true,
                            ) || mimeContent.startsWith("application/x-cbz", ignoreCase = true)
                        ) {
                            return "cbz"
                        }
                    } catch (_: Exception) {}
                }

                // 2. Comic verification:
                // ComicRack / Komga metadata or comic images
                if (zip.getEntry("ComicInfo.xml") != null) {
                    return "cbz"
                }

                val hasImages =
                    zip.entries().asSequence().any { entry ->
                        if (entry.isDirectory) return@any false
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        ext in comicImageExtensions
                    }
                if (hasImages) {
                    return "cbz"
                }
            }

            // 3. Fallback based on hint or file extension
            val normalizedHint = containerHint?.lowercase()?.removePrefix(".")
            if (normalizedHint in BOOK_EXTENSIONS) {
                return normalizedHint!!
            }
            if (normalizedHint == "zip") {
                return "cbz"
            }

            val fileExt = file.extension.lowercase()
            if (fileExt in BOOK_EXTENSIONS) {
                return fileExt
            }

            return "epub"
        }

        fun safeMoveOrCopy(source: File, target: File): File {
            if (source.absolutePath == target.absolutePath) return source
            if (target.exists()) {
                target.delete()
            }
            if (source.renameTo(target)) {
                return target
            }
            try {
                source.copyTo(target, overwrite = true)
                source.delete()
                if (target.exists() && target.length() > 0) {
                    return target
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to copy file from ${source.name} to ${target.name}")
            }
            return source
        }

        fun buildFormatHints(ext: String): FormatHints {
            val mediaType =
                when (ext.lowercase()) {
                    "cbz" -> MediaType.CBZ
                    "epub" -> MediaType.EPUB
                    "pdf" -> MediaType.PDF
                    else -> null
                }
            return FormatHints(mediaTypes = listOfNotNull(mediaType)).addFileExtension(ext)
        }
    }
}
