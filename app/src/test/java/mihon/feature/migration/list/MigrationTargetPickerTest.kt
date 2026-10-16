package mihon.feature.migration.list

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MigrationTargetPickerTest {

    private data class Src(val id: Long)
    private data class Match(val source: Long, val latest: Double)

    private val a = Src(1)
    private val b = Src(2)
    private val c = Src(3)

    private fun behind(currentLatest: Double?, currentCount: Int, targetLatest: Double?, targetCount: Int) =
        MigrationTargetPicker.isBehind(currentLatest, currentCount, targetLatest, targetCount)

    // --- the hard rule: never move an entry to a source with fewer chapters ---

    @Test
    fun `fewer chapters is behind, equal or more is not`() {
        behind(100.0, 100, 99.0, 99) shouldBe true
        behind(100.0, 100, 100.0, 100) shouldBe false
        behind(100.0, 100, 120.0, 120) shouldBe false
        behind(100.5, 101, 100.0, 100) shouldBe true // a half chapter counts
    }

    @Test
    fun `a match with no chapters is always behind`() {
        behind(12.0, 12, null, 0) shouldBe true
        behind(-1.0, 3, null, 0) shouldBe true // was accepted before: "none" compared as chapter 0
    }

    @Test
    fun `unnumbered chapters do not decide the comparison`() {
        behind(50.0, 52, -1.0, 4) shouldBe true // numbered here, only unnumbered there
        behind(-1.0, 3, -1.0, 2) shouldBe true // only unnumbered on both: compare counts
        behind(-1.0, 3, -1.0, 3) shouldBe false
        behind(-1.0, 3, 10.0, 10) shouldBe false
    }

    @Test
    fun `an entry with no chapters can go anywhere`() {
        behind(null, 0, null, 0) shouldBe false
    }

    // --- spreading ---

    @Test
    fun `least loaded source is tried first`() {
        MigrationTargetPicker.orderByLoad(listOf(a, b, c), mapOf(1L to 50, 2L to 3, 3L to 20)) { it.id } shouldBe
            listOf(b, c, a)
    }

    @Test
    fun `ties and unused sources keep priority order`() {
        MigrationTargetPicker.orderByLoad(listOf(a, b, c), mapOf(1L to 5, 2L to 5)) { it.id } shouldBe
            listOf(c, a, b)
        MigrationTargetPicker.orderByLoad(listOf(a, b, c), emptyMap()) { it.id } shouldBe listOf(a, b, c)
    }

    @Test
    fun `assignments spread a batch evenly`() = runBlocking<Unit> {
        val load = mutableMapOf<Long, Int>()
        repeat(9) {
            val ordered = MigrationTargetPicker.orderByLoad(listOf(a, b, c), load) { it.id }
            val picked = MigrationTargetPicker.firstUpToDate(ordered, isBehind = { false }) { Match(it.id, 10.0) }!!
            load.merge(picked.source, 1, Int::plus)
        }
        load shouldBe mapOf(1L to 3, 2L to 3, 3L to 3)
    }

    // --- picking ---

    @Test
    fun `a match that is behind falls through to the next source`() = runBlocking<Unit> {
        val searched = mutableListOf<Long>()
        MigrationTargetPicker.firstUpToDate(listOf(a, b, c), isBehind = { m: Match -> m.latest < 50.0 }) {
            searched += it.id
            Match(it.id, if (it == a) 40.0 else 60.0)
        } shouldBe Match(2, 60.0)
        searched shouldBe listOf(1L, 2L) // stops at the first good match: no extra requests
    }

    @Test
    fun `if every match is behind nothing is picked`() = runBlocking<Unit> {
        MigrationTargetPicker.firstUpToDate(listOf(a, b, c), isBehind = { _: Match -> true }) {
            Match(it.id, 1.0)
        } shouldBe null
    }

    @Test
    fun `sources without the title are skipped`() = runBlocking<Unit> {
        MigrationTargetPicker.firstUpToDate(listOf(a, b), isBehind = { _: Match -> false }) {
            if (it == a) null else Match(it.id, 5.0)
        } shouldBe Match(2, 5.0)
    }
}
