package mihon.feature.migration.list.search

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AlternativeTitlesTest {

    // Formats as they appear in a real library backup.

    @Test
    fun `Comick bulleted block`() {
        AlternativeTitles.fromDescription(
            "A battle between lessers and the strongest!\n\nAlternative Titles:\n• Inferior Psychic Underworld Returnee\n" +
                "• 魔界帰りの劣等能力者\n• Makai Kaeri no Rettou Nouryokusha",
        ) shouldBe listOf("Inferior Psychic Underworld Returnee", "魔界帰りの劣等能力者", "Makai Kaeri no Rettou Nouryokusha")
    }

    @Test
    fun `Comix plain block stops at the blank line before the metadata`() {
        AlternativeTitles.fromDescription(
            "is the mission given to Chul Ho Kang!\n\nAlternative Names:\n이차원 용병\nOther World Warrior\n" +
                "Two-Dimensional Mercenary\n\nYear: 2017\nLanguage: KO\nContent rating: Safe",
        ) shouldBe listOf("이차원 용병", "Other World Warrior", "Two-Dimensional Mercenary")
    }

    @Test
    fun `Bato block is read the same way`() {
        AlternativeTitles.fromDescription("tags\n\nAlternative Names:\n• First Title\n• 第二") shouldBe
            listOf("First Title", "第二")
    }

    @Test
    fun `no block, blank or missing description gives nothing`() {
        AlternativeTitles.fromDescription("Just a synopsis.") shouldBe emptyList()
        AlternativeTitles.fromDescription("") shouldBe emptyList()
        AlternativeTitles.fromDescription(null) shouldBe emptyList()
    }

    @Test
    fun `header must be its own line, not a phrase in the synopsis`() {
        AlternativeTitles.fromDescription("He has alternative titles: many.\nNot a list") shouldBe emptyList()
    }

    @Test
    fun `duplicates and single characters are dropped, and the list is capped`() {
        val many = (1..50).joinToString("\n") { "Title $it" }
        AlternativeTitles.fromDescription("Alternative Titles:\n• A\n• Same\n• Same\n$many").let {
            it.first() shouldBe "Same"
            it.size shouldBe 30
        }
    }

    // Matching, through the real search engine.

    private class Engine : BaseSmartSearchEngine<String>() {
        override fun getTitle(result: String) = result
        suspend fun regular(results: List<String>, title: String, alternatives: List<String> = emptyList()) =
            regularSearch({ results }, title, alternatives)
        suspend fun deep(results: List<String>, title: String, alternatives: List<String> = emptyList()) =
            deepSearch({ results }, title, alternatives)
    }

    private val romaji = "Naze Boku no Sekai o Daremo Oboeteinai no ka?"
    private val english = "Why Does Nobody Remember Me in This World?"
    private val results = listOf(english, "Why Does Everyone Hate Me?")

    @Test
    fun `a source listing the English title was missed when the entry is stored under romaji`() = runBlocking<Unit> {
        Engine().regular(results, romaji) shouldBe null
    }

    @Test
    fun `with the alternative titles it is found`() = runBlocking<Unit> {
        Engine().regular(results, romaji, listOf("なぜ僕の世界を誰も覚えていないのか？", english)) shouldBe english
        Engine().deep(results, romaji, listOf(english)) shouldBe english
    }

    @Test
    fun `alternative titles do not make an unrelated result acceptable`() = runBlocking<Unit> {
        Engine().regular(listOf("Solo Leveling", "Omniscient Reader"), romaji, listOf(english)) shouldBe null
    }
}
