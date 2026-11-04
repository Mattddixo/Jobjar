package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SpendingTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun done(id: Long, title: String, cents: Long?, at: Long?, category: String = "", parentId: Long? = null) =
        Job(id = id, title = title, estimatedMinutes = 10, category = category, isDone = true, completedAt = at, costCents = cents, parentId = parentId)

    private fun repeating(id: Long, title: String, cents: Long, times: Int, category: String = "") =
        Job(id = id, title = title, estimatedMinutes = 10, category = category, recurrenceDays = 7, completionCount = times, costCents = cents)

    private fun finished(jobId: Long, at: Long) = Completion(jobId = jobId, title = "", estimatedMinutes = 10, completedAt = at)

    private val jobs = listOf(
        done(1, "Fix tap", 4_000, at(2025, 3, 2), "House"),
        done(2, "Paint fence", 12_000, at(2026, 5, 1), "Garden"),
        // Five finishes, three of them logged.
        repeating(3, "Mow lawn", 2_000, times = 5, category = "Garden"),
        done(4, "Free job", null, at(2026, 1, 1), "House"),
        Job(id = 5, title = "Not done yet", estimatedMinutes = 10, costCents = 9_900),
        Job(id = 6, title = "Bathroom", estimatedMinutes = 60, category = "House"),
        done(7, "Buy tiles", 3_000, at(2026, 2, 3), "House", parentId = 6)
    )
    private val history = listOf(
        finished(3, at(2025, 9, 1)),
        finished(3, at(2026, 4, 1)),
        finished(3, at(2026, 6, 1))
    )

    @Test
    fun allTimeMatchesTheStatsTotalAndCountsEveryRepeat() {
        val all = spendingBreakdown(jobs, history, zone = zone)
        assertEquals(4_000L + 12_000 + 5 * 2_000 + 3_000, all.totalCents)
        assertEquals(listOf("Garden", "House"), all.groups.map { it.category })
        val garden = all.groups.first()
        assertEquals(listOf("Paint fence", "Mow lawn"), garden.items.map { it.title })
        val mow = garden.items[1]
        assertEquals(5, mow.times)
        assertEquals(10_000L, mow.cents)
        assertEquals(at(2026, 6, 1), mow.lastDoneAt)
        // The two finishes from before the history aren't in any year.
        assertEquals(4_000L, all.undatedCents)
        assertEquals(listOf(2026, 2025), all.years)
        assertEquals("Bathroom", all.groups[1].items.first { it.title == "Buy tiles" }.parentTitle)
    }

    @Test
    fun aYearOnlyCountsWhatWasFinishedThen() {
        val y2025 = spendingBreakdown(jobs, history, year = 2025, zone = zone)
        assertEquals(4_000L + 2_000, y2025.totalCents)
        assertEquals(1, y2025.groups.first { it.category == "Garden" }.items.single().times)

        val y2026 = spendingBreakdown(jobs, history, year = 2026, zone = zone)
        assertEquals(12_000L + 2 * 2_000 + 3_000, y2026.totalCents)
        assertNull(y2026.groups.flatMap { it.items }.firstOrNull { it.title == "Fix tap" })
    }

    @Test
    fun nothingWithACostMeansNothingToShow() {
        val none = spendingBreakdown(listOf(done(1, "Free", null, at(2026, 1, 1))), emptyList(), zone = zone)
        assertEquals(0L, none.totalCents)
        assertEquals(emptyList<Int>(), none.years)
    }
}
