package dev.jdtech.jellyfin.book.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.util.Url

/** Progress helpers shared by the reader and its unit tests. */
object BookProgress {
    const val TICKS_PER_PAGE = 10_000L
    const val JELLYFIN_EPUB_TICKS_TOTAL = 10_000_000L
    const val LEGACY_VIRTUAL_TICKS_TOTAL = 1_000_000_000L

    /**
     * Calculates total ticks used for progress reporting and tracking. For EPUB, Jellyfin
     * standardizes RunTimeTicks as 10,000,000L (1 second). For Divina (CBZ/CBR/PDF), Jellyfin
     * standardizes RunTimeTicks as pageCount * 10,000L.
     */
    fun calculateTotalTicks(pub: Publication?, serverRuntimeTicks: Long = 0L): Long {
        if (serverRuntimeTicks > 0L) {
            return serverRuntimeTicks
        }
        if (pub?.conformsTo(Publication.Profile.DIVINA) == true && pub.readingOrder.isNotEmpty()) {
            return pub.readingOrder.size * TICKS_PER_PAGE
        }
        return JELLYFIN_EPUB_TICKS_TOTAL
    }

    /** Calculates playback position ticks and normalized progression for a locator. */
    fun calculateProgressTicks(
        locator: Locator,
        pub: Publication?,
        serverRuntimeTicks: Long = 0L,
    ): Pair<Long, Double> {
        val totalTicks = calculateTotalTicks(pub, serverRuntimeTicks)
        if (pub?.conformsTo(Publication.Profile.DIVINA) == true) {
            val totalPages = pub.readingOrder.size
            val pageIndex =
                locator.locations.position?.let { it - 1 }
                    ?: pub.readingOrder.indexOfFirstWithHref(locator.href)?.takeIf { it >= 0 }
                    ?: if (totalPages > 0) {
                        ((locator.locations.totalProgression ?: 0.0) * totalPages).toInt()
                    } else {
                        0
                    }
            val clampedIndex = if (totalPages > 0) pageIndex.coerceIn(0, totalPages - 1) else 0
            val progression =
                if (totalPages > 0) {
                    (clampedIndex.toDouble() / totalPages).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
            val ticks =
                if (totalTicks == totalPages * TICKS_PER_PAGE) {
                    clampedIndex * TICKS_PER_PAGE
                } else if (totalPages > 0) {
                    (progression * totalTicks).toLong()
                } else {
                    0L
                }
            return Pair(ticks.coerceIn(0L, totalTicks), progression)
        } else {
            val progression = (locator.locations.totalProgression ?: 0.0).coerceIn(0.0, 1.0)
            val ticks = (progression * totalTicks).toLong().coerceIn(0L, totalTicks)
            return Pair(ticks, progression)
        }
    }

    /**
     * Calculates a normalized progression (0.0 to 1.0) from the server's position ticks. Handles
     * legacy virtual ticks (1,000,000,000L scale) gracefully if present on older records.
     */
    fun calculateProgressionFromServerTicks(
        serverPositionTicks: Long,
        totalTicks: Long,
    ): Double {
        if (serverPositionTicks <= 0L) return 0.0
        return if (
            serverPositionTicks > totalTicks &&
                totalTicks > 0L &&
                serverPositionTicks <= LEGACY_VIRTUAL_TICKS_TOTAL
        ) {
            (serverPositionTicks.toDouble() / LEGACY_VIRTUAL_TICKS_TOTAL).coerceIn(0.0, 1.0)
        } else if (totalTicks > 0L) {
            (serverPositionTicks.toDouble() / totalTicks).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    fun calculateEpubChapterProgress(
        locator: Locator,
        readingOrderIndex: Int?,
        positionsByReadingOrder: List<List<Locator>>,
        cachedChapterHref: Url? = null,
        cachedPage: Int? = null,
        cachedTotalPages: Int? = null,
        isScrollMode: Boolean = false,
    ): Pair<Int, Int> {
        val normalizedHref = locator.href.removeFragment().removeQuery()
        val totalPages =
            if (
                !isScrollMode &&
                    cachedChapterHref == normalizedHref &&
                    cachedTotalPages != null &&
                    cachedTotalPages > 0
            ) {
                cachedTotalPages
            } else if (
                readingOrderIndex != null &&
                    readingOrderIndex in positionsByReadingOrder.indices
            ) {
                positionsByReadingOrder[readingOrderIndex].size.coerceAtLeast(1)
            } else {
                1
            }
        val currentPage =
            if (
                !isScrollMode &&
                    cachedChapterHref == normalizedHref &&
                    cachedPage != null &&
                    cachedPage in 1..totalPages
            ) {
                cachedPage
            } else {
                val progression = locator.locations.progression ?: 0.0
                ((progression + 1e-5) * totalPages).toInt().coerceIn(0, totalPages - 1) + 1
            }
        return currentPage to totalPages
    }

    private fun findExactTitleInToc(links: List<Link>, targetHref: Url): String? {
        for (link in links) {
            if (link.url() == targetHref && !link.title.isNullOrBlank()) {
                return link.title!!.trim()
            }
            val childResult = findExactTitleInToc(link.children, targetHref)
            if (!childResult.isNullOrBlank()) {
                return childResult
            }
        }
        return null
    }

    private fun findNormalizedTitleInToc(links: List<Link>, targetNormalized: Url): String? {
        for (link in links) {
            if (
                link.url().removeFragment().removeQuery() == targetNormalized &&
                    !link.title.isNullOrBlank()
            ) {
                return link.title!!.trim()
            }
            val childResult = findNormalizedTitleInToc(link.children, targetNormalized)
            if (!childResult.isNullOrBlank()) {
                return childResult
            }
        }
        return null
    }

    fun findTitleInToc(links: List<Link>, targetHref: Url): String? {
        val exactResult = findExactTitleInToc(links, targetHref)
        if (!exactResult.isNullOrBlank()) {
            return exactResult
        }
        val targetNormalized = targetHref.removeFragment().removeQuery()
        return findNormalizedTitleInToc(links, targetNormalized)
    }

    fun resolveChapterTitle(
        locator: Locator?,
        tableOfContents: List<Link> = emptyList(),
        readingOrder: List<Link> = emptyList(),
        bookTitle: String = "",
    ): String {
        val directTitle = locator?.title?.trim()
        if (!directTitle.isNullOrEmpty()) {
            return directTitle
        }

        if (locator != null) {
            val tocTitle = findTitleInToc(tableOfContents, locator.href)
            if (!tocTitle.isNullOrBlank()) {
                return tocTitle
            }

            val targetNormalized = locator.href.removeFragment().removeQuery()
            val readingOrderItem =
                readingOrder.firstOrNull {
                    it.url().removeFragment().removeQuery() == targetNormalized
                }
            val readingOrderTitle = readingOrderItem?.title?.trim()
            if (!readingOrderTitle.isNullOrEmpty()) {
                return readingOrderTitle
            }
        }

        return bookTitle.trim()
    }
}
