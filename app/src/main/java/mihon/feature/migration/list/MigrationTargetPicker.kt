package mihon.feature.migration.list

/**
 * How batch migration picks the source an entry moves to. Kept apart from
 * [MigrationListScreenModel] so the choice can be tested without extensions.
 *
 * The one hard rule: an automatic pick is never behind the entry being migrated. Moving an entry
 * to a source with fewer chapters loses reading material, so such a match is skipped, and if no
 * source is up to date the entry stays where it is.
 */
internal object MigrationTargetPicker {

    /**
     * Whether a match with [targetLatest] / [targetCount] would lose chapters compared to the
     * current entry's [currentLatest] / [currentCount]. Latest values are chapter numbers, where a
     * negative number means the chapter could not be numbered.
     */
    fun isBehind(currentLatest: Double?, currentCount: Int, targetLatest: Double?, targetCount: Int): Boolean {
        if (currentCount == 0) return false // nothing to lose
        if (targetCount == 0) return true // an empty match is never up to date
        val current = currentLatest?.takeIf { it >= 0 }
        val target = targetLatest?.takeIf { it >= 0 }
        return when {
            current == null -> targetCount < currentCount // only unnumbered chapters: compare counts
            target == null -> true // numbered chapters here, none there
            else -> target < current
        }
    }

    /**
     * [sources] reordered so the one holding the fewest library entries is tried first, for
     * "Spread across sources". Ties keep the user's priority order, so with an empty library this
     * is the plain priority order.
     */
    fun <S> orderByLoad(sources: List<S>, load: Map<Long, Int>, id: (S) -> Long): List<S> =
        sources.withIndex()
            .sortedWith(compareBy<IndexedValue<S>> { load[id(it.value)] ?: 0 }.thenBy { it.index })
            .map { it.value }

    /**
     * Searches [sources] in order and returns the first match that is not behind, or null.
     * Stops at the first acceptable match, so it sends no more requests than it needs to.
     */
    suspend fun <S, R> firstUpToDate(
        sources: List<S>,
        isBehind: (R) -> Boolean,
        search: suspend (S) -> R?,
    ): R? {
        for (source in sources) {
            val result = search(source) ?: continue
            if (!isBehind(result)) return result
        }
        return null
    }
}
