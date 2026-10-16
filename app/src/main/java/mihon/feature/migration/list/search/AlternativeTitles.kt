package mihon.feature.migration.list.search

/**
 * Other titles a series is known by, read from its description. Sources disagree on which title
 * they list (romaji, English, native), so matching only the title stored in the library misses
 * the same series on a source that uses another one. Comick, Comix and Bato write these as an
 * "Alternative Titles:" / "Alternative Names:" block, one title per line, optionally bulleted,
 * ended by a blank line.
 */
internal object AlternativeTitles {

    private val HEADER = Regex(
        """^[ \t]*Alternative (?:Titles|Names)[ \t]*:[ \t]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** Upper bound so a malformed description cannot turn into hundreds of comparisons. */
    private const val MAX_TITLES = 30

    fun fromDescription(description: String?): List<String> {
        if (description.isNullOrBlank()) return emptyList()
        val header = HEADER.find(description) ?: return emptyList()
        return description.substring(header.range.last + 1)
            .lineSequence()
            .dropWhile { it.isBlank() }
            .takeWhile { it.isNotBlank() }
            .map { it.trim().trimStart('•', '·', '*', '-').trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_TITLES)
            .toList()
    }
}
