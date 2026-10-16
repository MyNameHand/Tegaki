package mihon.feature.migration.list.search

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

class SmartSourceSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<SManga>(extraSearchParams) {

    override fun getTitle(result: SManga) = result.originalTitle

    suspend fun regularSearch(source: Source, title: String, alternativeTitles: List<String> = emptyList()): Manga? {
        return regularSearch(makeSearchAction(source), title, alternativeTitles).let {
            it?.toDomainManga(source.id)
        }
    }

    suspend fun deepSearch(source: Source, title: String, alternativeTitles: List<String> = emptyList()): Manga? {
        return deepSearch(makeSearchAction(source), title, alternativeTitles).let {
            it?.toDomainManga(source.id)
        }
    }

    private fun makeSearchAction(source: Source): SearchAction<SManga> = { query ->
        source.getSearchManga(1, query, source.getFilterList()).mangas
    }
}
