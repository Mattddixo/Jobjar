package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ComingUpTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = at(2026, 10, 29, 10)
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun repeating(title: String, due: Long?) =
        Job(id = nextId++, title = title, estimatedMinutes = 10, recurrenceDays = 7, nextDueAt = due)

    @Test
    fun groupsAndOrder() {
        val jobs = listOf(
            repeating("Later", at(2026, 12, 1)),
            repeating("Soon", at(2026, 11, 3)),
            repeating("Today", at(2026, 10, 29, 18)),
            repeating("Due now", null),
            repeating("Overdue", at(2026, 10, 26)),
            Job(id = 99, title = "One-off", estimatedMinutes = 5)
        )
        val items = comingUp(jobs, rangeDays = 14, now = now, zone = zone)
        assertEquals(listOf("Overdue", "Due now", "Today", "Soon", "Later"), items.map { it.job.title })
        assertEquals(
            listOf(ComingUpGroup.OVERDUE, ComingUpGroup.TODAY, ComingUpGroup.TODAY, ComingUpGroup.SOON, ComingUpGroup.LATER),
            items.map { it.group }
        )
        assertEquals(ComingUpCounts(overdue = 1, today = 2, soon = 1), items.counts())
        assertEquals("a shorter range moves it to later", 0, comingUp(jobs, 3, now, zone).counts().soon)
    }
}
