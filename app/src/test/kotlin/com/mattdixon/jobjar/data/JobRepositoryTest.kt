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

    private fun dueDate(id: Long) = dao.row(id).nextDueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    private suspend fun addRule(title: String, rule: RepeatRule) =
        repo.addJob(Job(title = title, estimatedMinutes = 20).withRepeatRule(rule))

    private val rego = RepeatRule(
        interval = 1, unit = RepeatUnit.YEAR, anchor = RepeatAnchor.SCHEDULE, monthDay = 20, month = 3, leadDays = 30
    )

    @Test
    fun yearlyJobWaitsForItsDateThenShowsInItsLeadWindow() = runTest {
        val job = addRule("Car registration", rego)
        assertEquals(java.time.LocalDate.of(2026, 3, 20), dueDate(job))
        assertTrue("2 Mar is inside the 30 days before", dao.row(job).isPending(now, zone))
        // Done early: next year's date, not a year from today.
        repo.toggleDone(job)
        assertEquals(java.time.LocalDate.of(2027, 3, 20), dueDate(job))
        assertFalse(dao.row(job).isPending(now, zone))
    }

    @Test
    fun changingJustTheNumberOrLeadKeepsTheDueDate() = runTest {
        val job = addRule("Car registration", rego)
        repo.toggleDone(job)
        repo.updateJob(dao.row(job).withRepeatRule(rego.copy(interval = 2, leadDays = 7)))
        assertEquals(java.time.LocalDate.of(2027, 3, 20), dueDate(job))
        // A different date is a new rule: starts from its next date.
        repo.updateJob(dao.row(job).withRepeatRule(rego.copy(monthDay = 25)))
        assertEquals(java.time.LocalDate.of(2026, 3, 25), dueDate(job))
    }

    @Test
    fun overdueJobsAreDrawnLikeHighPriority() = runTest {
        val seeded = JobRepository(dao, calendar, DirectTransactions, clock = { now }, zone = { zone }, random = kotlin.random.Random(7))
        now = LocalDateTime.of(2026, 2, 27, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val overdue = addRule("Filter", RepeatRule(interval = 1, unit = RepeatUnit.MONTH, anchor = RepeatAnchor.SCHEDULE, monthDay = 1))
        val other = repo.addJob(Job(title = "Shelf", estimatedMinutes = 20, priority = Priority.LOW))
        now = LocalDateTime.of(2026, 3, 5, 9, 0).atZone(zone).toInstant().toEpochMilli() // due on the 1st
        assertTrue(dao.row(overdue).isOverdue(now, zone))
        val picks = (1..400).mapNotNull { seeded.drawJob(30, emptySet(), emptyList())?.job?.id }
        val share = picks.count { it == overdue } / picks.size.toDouble()
        val expected = priorityWeight(Priority.HIGH) / (priorityWeight(Priority.HIGH) + priorityWeight(Priority.LOW))
        assertEquals(expected, share, 0.07)
        assertTrue(other in picks)
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
    fun reschedulingReplacesTheEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        val oldId = dao.row(job).calendarEventId
        repo.scheduleJob(job, now + 7_200_000)
        val newId = dao.row(job).calendarEventId!!
        assertTrue(newId != oldId)
        assertEquals("the old one is gone", setOf(newId), calendar.events.keys)
        assertEquals(now + 7_200_000, calendar.events.getValue(newId).start)
    }

    @Test
    fun reschedulingAfterTheEventWasDeletedInTheCalendarStillWritesOne() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        // Deleted in the calendar app; the app hasn't noticed yet.
        calendar.events.clear()
        assertTrue(repo.scheduleJob(job, now + 7_200_000))
        assertEquals(now + 7_200_000, calendar.events.values.single().start)
        assertEquals(calendar.events.keys.single(), dao.row(job).calendarEventId)
    }

    @Test
    fun unschedulingThenReschedulingWritesANewEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        repo.unscheduleJob(job)
        assertTrue(calendar.events.isEmpty())
        repo.scheduleJob(job, now + 7_200_000)
        assertEquals(now + 7_200_000, calendar.events.values.single().start)
        assertEquals(calendar.events.keys.single(), dao.row(job).calendarEventId)
    }

    @Test
    fun anEventIdThatIsntOursIsNeverDeleted() = runTest {
        // Like after restoring a backup on a new phone: the stored id is someone else's event.
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

    // --- Checklists ---

    @Test
    fun checklistIsSavedTrimmedWithoutBlankLines() = runTest {
        val job = add("Paint fence")
        repo.setChecklist(job, listOf(ChecklistItem(" Sandpaper "), ChecklistItem("  "), ChecklistItem("Brushes", checked = true)))
        assertEquals(listOf(ChecklistItem("Sandpaper"), ChecklistItem("Brushes", checked = true)), dao.row(job).checklist)
    }

    @Test
    fun editingTheJobKeepsItsChecklist() = runTest {
        val job = add("Paint fence")
        repo.setChecklist(job, listOf(ChecklistItem("Sandpaper")))
        repo.updateJob(dao.row(job).copy(title = "Paint the fence", checklist = emptyList()))
        assertEquals(listOf(ChecklistItem("Sandpaper")), dao.row(job).checklist)
    }

    @Test
    fun completingARepeatingJobUnticksItsChecklistAndUndoBringsTheTicksBack() = runTest {
        val job = add("Weekly clean", recurrenceDays = 7)
        repo.setChecklist(job, listOf(ChecklistItem("Kitchen", checked = true), ChecklistItem("Bathroom")))
        val snapshot = repo.toggleDone(job)!!
        assertTrue(dao.row(job).checklist.none { it.checked })
        repo.undo(snapshot)
        assertTrue(dao.row(job).checklist.first().checked)
    }

    @Test
    fun completingAOneOffJobLeavesItsTicksAlone() = runTest {
        val job = add("Paint fence")
        repo.setChecklist(job, listOf(ChecklistItem("Sandpaper", checked = true)))
        repo.toggleDone(job)
        assertTrue(dao.row(job).checklist.single().checked)
    }

    @Test
    fun deletingAChecklistCanBeUndone() = runTest {
        val job = add("Paint fence")
        repo.setChecklist(job, listOf(ChecklistItem("Sandpaper")))
        val snapshot = repo.deleteChecklist(job)!!
        assertTrue(dao.row(job).checklist.isEmpty())
        repo.undo(snapshot)
        assertEquals(listOf(ChecklistItem("Sandpaper")), dao.row(job).checklist)
        assertNull("nothing to delete", repo.deleteChecklist(add("Other")))
    }

    @Test
    fun editingAJobSavesItsCost() = runTest {
        val job = add("Paint fence")
        repo.updateJob(dao.row(job).copy(costCents = 4500))
        assertEquals(4500L, dao.row(job).costCents)
        repo.updateJob(dao.row(job).copy(costCents = null))
        assertNull(dao.row(job).costCents)
    }

    @Test
    fun deletingABookedJobDeletesItsEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        repo.deleteJob(job)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun deletingAParentDeletesItsBookedSubtasksEventsAndUndoBringsThemBack() = runTest {
        val parent = add("Garage", 60)
        val sub = add("Sweep", 20, parentId = parent)
        repo.scheduleJob(sub, now + 3_600_000)
        val snapshot = repo.deleteJob(parent)!!
        assertTrue(calendar.events.isEmpty())
        repo.undo(snapshot)
        val event = calendar.events.getValue(dao.row(sub).calendarEventId!!)
        assertEquals(now + 3_600_000, event.start)
    }

    @Test
    fun editingNotesOfABookedJobUpdatesItsEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        repo.updateJob(dao.row(job).copy(notes = "Bring the forms"))
        assertEquals("Bring the forms", calendar.events.values.single().notes)
    }

    @Test
    fun importDeletesEventsOfJobsItReplaces() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        repo.replaceAllJobs(listOf(Job(id = 1, title = "Something else", estimatedMinutes = 10)))
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun importingTheSameBookingKeepsItsEvent() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + 3_600_000)
        val eventId = dao.row(job).calendarEventId
        repo.replaceAllJobs(listOf(dao.row(job)))
        assertEquals(setOf(eventId), calendar.events.keys)
        assertEquals(eventId, dao.row(job).calendarEventId)
    }

    @Test
    fun importWritesEventsForUpcomingBookingsOnly() = runTest {
        repo.replaceAllJobs(
            listOf(
                Job(id = 1, title = "Upcoming", estimatedMinutes = 30, scheduledDate = now + 3_600_000, calendarEventId = 777),
                Job(id = 2, title = "Already past", estimatedMinutes = 30, scheduledDate = now - 3_600_000)
            )
        )
        val event = calendar.events.values.single()
        assertEquals("Upcoming", event.title)
        assertEquals(now + 3_600_000 + 30 * 60_000, event.end)
        assertNotNull(dao.row(1).calendarEventId)
        assertNull(dao.row(2).calendarEventId)
    }

    @Test
    fun eventDescriptionHasNotesThenWhereItCameFrom() {
        assertTrue(eventDescription("").startsWith("Scheduled from Job Jar"))
        assertTrue(eventDescription(" Bring the forms ").startsWith("Bring the forms\n\nScheduled from Job Jar"))
    }

    // --- Missed bookings ---

    private val hour = 3_600_000L

    @Test
    fun aBookedJobStaysOutOfTheJarUntilItsSlotIsOverThenComesBack() = runTest {
        val job = add("Paint fence", 60)
        repo.scheduleJob(job, now + hour)
        assertFalse(dao.row(job).isAvailableToDraw(now))
        assertTrue(dao.row(job).isBooked(now))

        // Slot is 1-2 hours from the start; during it, still booked.
        now += 90 * 60_000
        assertTrue(dao.row(job).isBooked(now))

        now += hour
        val row = dao.row(job)
        assertTrue(row.isMissed(now))
        assertFalse(row.isBooked(now))
        assertTrue(row.isAvailableToDraw(now))
        assertEquals(job, repo.drawJob(120, emptySet(), emptyList())?.job?.id)
    }

    @Test
    fun startingAMissedJobKeepsItsPastEventButUnlinksIt() = runTest {
        val job = add("Paint fence", 30)
        repo.scheduleJob(job, now + hour)
        now += 3 * hour
        repo.toggleInProgress(job)
        assertNull(dao.row(job).scheduledDate)
        assertEquals("the past event stays as a record", 1, calendar.events.size)
    }

    @Test
    fun completingOrDeletingOnlyRemovesUpcomingEvents() = runTest {
        val upcoming = add("Upcoming", 30)
        val past = add("Past", 30)
        repo.scheduleJob(upcoming, now + 5 * hour)
        repo.scheduleJob(past, now + hour)
        now += 3 * hour
        repo.toggleDone(upcoming)
        repo.deleteJob(past)
        assertEquals(listOf("Past"), calendar.events.values.map { it.title })
    }

    @Test
    fun undoingAMissedJobsStartDoesNotAddASecondEvent() = runTest {
        val job = add("Paint fence", 30)
        repo.scheduleJob(job, now + hour)
        now += 3 * hour
        val snapshot = repo.toggleDone(job)!!
        repo.undo(snapshot)
        assertEquals(1, calendar.events.size)
        assertTrue(dao.row(job).isMissed(now))
    }

    @Test
    fun reschedulingAMissedJobKeepsThePastEventAndAddsANewOne() = runTest {
        val job = add("Paint fence", 30)
        repo.scheduleJob(job, now + hour)
        val pastId = dao.row(job).calendarEventId!!
        now += 3 * hour
        repo.scheduleJob(job, now + hour)
        assertEquals(2, calendar.events.size)
        assertTrue("the past one stays as a record", pastId in calendar.events)
        assertTrue(dao.row(job).calendarEventId != pastId)
        assertTrue(dao.row(job).isBooked(now))
    }

    // --- Picking up calendar changes ---

    @Test
    fun movingTheEventInTheCalendarMovesTheBooking() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + hour)
        calendar.moveEvent(dao.row(job).calendarEventId!!, now + 26 * hour)
        repo.syncCalendar()
        assertEquals(now + 26 * hour, dao.row(job).scheduledDate)
    }

    @Test
    fun deletingTheEventInTheCalendarUnbooksTheJob() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + hour)
        calendar.events.clear()
        repo.syncCalendar()
        assertNull(dao.row(job).scheduledDate)
        assertNull(dao.row(job).calendarEventId)
        assertTrue(dao.row(job).isAvailableToDraw(now))
    }

    @Test
    fun anEventWithANewIdGetsRelinked() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + hour)
        val newId = calendar.rekeyEvent(dao.row(job).calendarEventId!!)
        repo.syncCalendar()
        assertEquals(newId, dao.row(job).calendarEventId)
        assertEquals(now + hour, dao.row(job).scheduledDate)
    }

    @Test
    fun withoutCalendarAccessBookingsAreLeftAlone() = runTest {
        val job = add("Dentist")
        repo.scheduleJob(job, now + hour)
        calendar.events.clear()
        calendar.permissionGranted = false
        repo.syncCalendar()
        assertEquals(now + hour, dao.row(job).scheduledDate)
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
