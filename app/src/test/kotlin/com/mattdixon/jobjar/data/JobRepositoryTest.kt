package com.mattdixon.jobjar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class JobRepositoryTest {
    private val zone = ZoneId.of("America/Toronto")
    private var now = LocalDateTime.of(2026, 3, 2, 9, 0).atZone(zone).toInstant().toEpochMilli() // a Monday
    private val dao = FakeJobDao()
    private val calendar = FakeCalendar()
    private val repo = JobRepository(dao, calendar, DirectTransactions, clock = { now }, zone = { zone })

    private suspend fun add(title: String, minutes: Int = 30, parentId: Long? = null, recurrenceDays: Int? = null, weekdays: Int? = null, category: String = "") =
        repo.addJob(Job(title = title, estimatedMinutes = minutes, parentId = parentId, recurrenceDays = recurrenceDays, repeatWeekdays = weekdays, category = category))

    // --- Completing parents and subtasks ---

    @Test
    fun completingParentCompletesOpenSubtasks() = runTest {
        val parent = add("Garage", 120)
        val a = add("Sweep", parentId = parent)
        val b = add("Sort", parentId = parent)
        repo.toggleDone(parent)
        assertTrue(dao.row(parent).isDone)
        assertTrue(dao.row(a).isDone)
        assertTrue(dao.row(b).isDone)
    }

    @Test
    fun reopeningParentReopensOnlyTheSubtasksItClosed() = runTest {
        val parent = add("Garage", 120)
        val byHand = add("Sweep", parentId = parent)
        val swept = add("Sort", parentId = parent)
        repo.toggleDone(byHand)
        now += 60_000
        repo.toggleDone(parent)
        repo.toggleDone(parent)
        assertFalse(dao.row(parent).isDone)
        assertTrue("finished by hand stays done", dao.row(byHand).isDone)
        assertFalse("closed by the parent reopens", dao.row(swept).isDone)
    }

    @Test
    fun parentAutoCompletesFromLastSubtaskAndReopeningItKeepsSubtasksDone() = runTest {
        val parent = add("Garage", 120)
        val a = add("Sweep", parentId = parent)
        val b = add("Sort", parentId = parent)
        repo.toggleDone(a)
        repo.toggleDone(b) // same millisecond as the parent's auto-complete
        assertTrue(dao.row(parent).isDone)
        repo.toggleDone(parent)
        assertTrue(dao.row(a).isDone)
        assertTrue(dao.row(b).isDone)
    }

    @Test
    fun reopeningASubtaskReopensItsParent() = runTest {
        val parent = add("Garage", 120)
        val a = add("Sweep", parentId = parent)
        repo.toggleDone(a)
        assertTrue(dao.row(parent).isDone)
        repo.toggleDone(a)
        assertFalse(dao.row(parent).isDone)
    }

    @Test
    fun parentGrowsToFitItsSubtasks() = runTest {
        val parent = add("Garage", 60)
        add("Sweep", 45, parentId = parent)
        add("Sort", 45, parentId = parent)
        assertEquals(90, dao.row(parent).estimatedMinutes)
    }

    // --- Undo ---

    @Test
    fun undoCompletionRestoresEverythingIncludingCalendarEvent() = runTest {
        val parent = add("Garage", 120)
        val sub = add("Sweep", parentId = parent)
        repo.scheduleJob(sub, now + 86_400_000)
        val eventBefore = dao.row(sub).calendarEventId
        assertNotNull(eventBefore)

        val snapshot = repo.toggleDone(parent)!!
        assertTrue(dao.row(sub).isDone)
        assertNull("completing unschedules", dao.row(sub).scheduledDate)
        assertTrue(calendar.events.isEmpty())

        repo.undo(snapshot)
        assertFalse(dao.row(parent).isDone)
        assertFalse(dao.row(sub).isDone)
        assertEquals(now + 86_400_000, dao.row(sub).scheduledDate)
        val eventAfter = dao.row(sub).calendarEventId!!
        assertEquals(sub, calendar.events.getValue(eventAfter).jobId)
    }

    @Test
    fun undoDeleteBringsBackParentAndSubtasks() = runTest {
        val parent = add("Garage", 120)
        val a = add("Sweep", parentId = parent)
        val b = add("Sort", parentId = parent)
        val snapshot = repo.deleteJob(parent)!!
        assertTrue(dao.all.isEmpty())
        repo.undo(snapshot)
        assertEquals(listOf(parent, a, b), dao.all.map { it.id })
        assertEquals(parent, dao.row(a).parentId)
    }

    @Test
    fun deletingASubtaskClearsSiblingDependencyAndUndoRestoresIt() = runTest {
        val parent = add("Garage", 120)
        val first = add("Sort", parentId = parent)
        val second = repo.addJob(Job(title = "Haul", estimatedMinutes = 30, parentId = parent, dependsOnSubtaskId = first))
        val snapshot = repo.deleteJob(first)!!
        assertNull(dao.row(second).dependsOnSubtaskId)
        repo.undo(snapshot)
        assertEquals(first, dao.row(second).dependsOnSubtaskId)
    }

    // --- Repeating jobs ---

    @Test
    fun repeatingJobCannotHaveSubtasks() = runTest {
        val chore = add("Water plants", recurrenceDays = 7)
        val failed = runCatching { add("Fern", parentId = chore) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun jobWithSubtasksIsSavedAsOneOffEvenIfAskedToRepeat() = runTest {
        val parent = add("Garage", 120)
        add("Sweep", parentId = parent)
        repo.updateJob(dao.row(parent).copy(recurrenceDays = 7))
        assertNull(dao.row(parent).recurrenceDays)
    }

    @Test
    fun completingIntervalJobCyclesItDstSafe() = runTest {
        // Saturday 7 March 2026, 9am; clocks go forward on Sunday 8 March in Toronto.
        now = LocalDateTime.of(2026, 3, 7, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val chore = add("Water plants", recurrenceDays = 1)
        repo.toggleDone(chore)
        val row = dao.row(chore)
        assertFalse(row.isDone)
        assertEquals(1, row.completionCount)
        val nextLocal = java.time.Instant.ofEpochMilli(row.nextDueAt!!).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 3, 8, 9, 0), nextLocal)
        assertFalse(row.isPending(now))
    }

    @Test
    fun restingRepeatingJobWakesWhenToggled() = runTest {
        val chore = add("Water plants", recurrenceDays = 7)
        repo.toggleDone(chore)
        repo.toggleDone(chore)
        assertNull(dao.row(chore).nextDueAt)
        assertEquals("waking doesn't undo the completion", 1, dao.row(chore).completionCount)
    }

    @Test
    fun weekdayJobCreatedOnAnotherDayWaitsForItsDay() = runTest {
        // now is a Monday; a Thursday-only job shouldn't be due yet.
        val chore = add("Bins", recurrenceDays = 7, weekdays = weekdayBit(java.time.DayOfWeek.THURSDAY))
        val due = java.time.Instant.ofEpochMilli(dao.row(chore).nextDueAt!!).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 3, 5, 0, 0), due)
    }

    @Test
    fun weekdayJobCompletedComesBackOnItsNextDay() = runTest {
        val monAndThu = weekdayBit(java.time.DayOfWeek.MONDAY) or weekdayBit(java.time.DayOfWeek.THURSDAY)
        val chore = add("Bins", recurrenceDays = 7, weekdays = monAndThu)
        assertNull("due today, a Monday", dao.row(chore).nextDueAt)
        repo.toggleDone(chore)
        val due = java.time.Instant.ofEpochMilli(dao.row(chore).nextDueAt!!).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 3, 5, 0, 0), due)
    }

    @Test
    fun turningRepeatOnClearsScheduleAndCalendarEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        assertEquals(1, calendar.events.size)
        repo.updateJob(dao.row(job).copy(recurrenceDays = 30))
        assertNull(dao.row(job).scheduledDate)
        assertTrue(calendar.events.isEmpty())
    }

    // --- Scheduling and the calendar ---

    @Test
    fun schedulingUsesTheCurrentRowNotAStaleCopy() = runTest {
        val job = add("Old title")
        val staleCopy = dao.row(job)
        repo.updateJob(staleCopy.copy(title = "New title"))
        repo.scheduleJob(staleCopy.id, now + 3_600_000)
        assertEquals("New title", dao.row(job).title)
        assertEquals("New title", calendar.events.values.single().title)
    }

    @Test
    fun reschedulingMovesTheSameEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        val id = dao.row(job).calendarEventId
        repo.scheduleJob(job, now + 7_200_000)
        assertEquals(id, dao.row(job).calendarEventId)
        assertEquals(1, calendar.events.size)
        assertEquals(now + 7_200_000, calendar.events.getValue(id!!).start)
    }

    @Test
    fun anEventIdThatIsntOursIsNeverDeleted() = runTest {
        // Simulates a backup restored onto a new phone: the stored id now belongs to someone else's event.
        calendar.events[555L] = FakeCalendar.Event(jobId = 9999, title = "Mum's birthday", start = 0, end = 0)
        val job = add("Dentist")
        dao.setSchedule(job, now + 3_600_000, 555L)
        repo.toggleDone(job)
        assertTrue("the stranger's event survives", 555L in calendar.events)
        assertNull(dao.row(job).calendarEventId)
    }

    @Test
    fun editingTitleOfABookedJobRenamesItsEvent() = runTest {
        val job = add("Dentist", 30)
        repo.scheduleJob(job, now + 3_600_000)
        repo.updateJob(dao.row(job).copy(title = "Dentist (cleaning)", estimatedMinutes = 60))
        val event = calendar.events.values.single()
        assertEquals("Dentist (cleaning)", event.title)
        assertEquals(now + 3_600_000 + 60 * 60_000, event.end)
    }

    @Test
    fun startingAScheduledJobUnschedulesIt() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        repo.toggleInProgress(job)
        assertTrue(dao.row(job).isInProgress)
        assertNull(dao.row(job).scheduledDate)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun schedulingWithoutCalendarPermissionStillBooksInApp() = runTest {
        calendar.permissionGranted = false
        val job = add("Dentist")
        val wroteEvent = repo.scheduleJob(job, now + 3_600_000)
        assertFalse(wroteEvent)
        assertEquals(now + 3_600_000, dao.row(job).scheduledDate)
    }

    // --- Categories ---

    @Test
    fun typedCategoryAdoptsExistingSpelling() = runTest {
        add("A", category = "Home")
        val b = add("B", category = "  home ")
        assertEquals("Home", dao.row(b).category)
    }

    @Test
    fun recasingACategoryOnlyOneJobHasSticks() = runTest {
        val job = add("A", category = "home")
        repo.updateJob(dao.row(job).copy(category = "Home"))
        assertEquals("Home", dao.row(job).category)
    }

    @Test
    fun mergeCategoryCaseUsesTheMostCommonSpelling() = runTest {
        dao.insert(Job(title = "A", estimatedMinutes = 5, category = "home"))
        dao.insert(Job(title = "B", estimatedMinutes = 5, category = "Home"))
        dao.insert(Job(title = "C", estimatedMinutes = 5, category = "Home"))
        repo.mergeCategoryCase()
        assertEquals(setOf("Home"), dao.all.map { it.category }.toSet())
    }

    // --- Drawing ---

    @Test
    fun drawSkipsBlockedScheduledInProgressAndRestingJobs() = runTest {
        val parent = add("Garage", 120)
        val first = add("Sort", 20, parentId = parent)
        repo.addJob(Job(title = "Haul", estimatedMinutes = 20, parentId = parent, dependsOnSubtaskId = first))
        val booked = add("Dentist", 20)
        repo.scheduleJob(booked, now + 3_600_000)
        val started = add("Door", 20)
        repo.toggleInProgress(started)
        val resting = add("Plants", 20, recurrenceDays = 7)
        repo.toggleDone(resting)

        val drawn = (1..50).mapNotNull { repo.drawJob(maxMinutes = 30, categories = emptySet(), excludeIds = emptyList())?.job?.id }.toSet()
        assertEquals(setOf(first), drawn)
    }
}
