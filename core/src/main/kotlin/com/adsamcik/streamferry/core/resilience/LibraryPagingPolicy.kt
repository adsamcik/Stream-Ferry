package com.adsamcik.streamferry.core.resilience

/**
 * Paginates a full Jellyfin library load so the whole library can be displayed reliably even over a
 * slow/spotty link (§8). Fetching thousands of items in one request risks timeouts; this policy
 * walks the library in bounded pages (`StartIndex`/`Limit`) and, combined with [RetryBudget],
 * recovers transient page failures with backoff instead of failing the whole browse.
 *
 * Pure-JVM and deterministic. The HTTP repository drives it: ask for [firstPage], fetch it
 * (retrying per the budget on transient errors), then call [nextPage] with what came back until it
 * returns null (done).
 *
 * @param pageSize requested items per page (clamped to [maxPageSize]).
 * @param maxPageSize hard ceiling so a misconfiguration cannot request an unbounded page.
 * @param maxPages absolute ceiling on the number of pages walked, so a server that keeps returning
 *   full pages with a missing/incorrect `TotalRecordCount` cannot drive an unbounded fetch loop.
 */
class LibraryPagingPolicy(
    pageSize: Int = DEFAULT_PAGE_SIZE,
    val maxPageSize: Int = MAX_PAGE_SIZE,
    maxPages: Int = DEFAULT_MAX_PAGES,
) {
    val pageSize: Int = pageSize.coerceIn(1, maxPageSize)
    val maxPages: Int = maxPages.coerceAtLeast(1)

    data class PageRequest(val startIndex: Int, val limit: Int)

    fun firstPage(): PageRequest = PageRequest(startIndex = 0, limit = pageSize)

    /**
     * Compute the next page to fetch, or null when the library has been fully read.
     *
     * @param current the page just fetched.
     * @param itemsInPage how many items that page actually returned.
     * @param totalRecordCount Jellyfin's reported total (the `TotalRecordCount` field), or null if
     *   the server did not provide it.
     */
    fun nextPage(current: PageRequest, itemsInPage: Int, totalRecordCount: Int?): PageRequest? {
        // A short page means we reached the end regardless of any (possibly stale) total.
        if (itemsInPage < current.limit) return null
        if (itemsInPage <= 0) return null
        val fetched = current.startIndex + itemsInPage
        if (totalRecordCount != null && fetched >= totalRecordCount) return null
        // Absolute ceiling: stop after maxPages full pages so a server reporting no/over-large total
        // (or a buggy/hostile one always returning a full page) cannot loop forever and accumulate
        // unbounded items in memory.
        val pagesFetched = fetched / pageSize
        if (pagesFetched >= maxPages) return null
        return PageRequest(startIndex = fetched, limit = pageSize)
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 200
        const val MAX_PAGE_SIZE = 500
        const val DEFAULT_MAX_PAGES = 1000
    }
}
