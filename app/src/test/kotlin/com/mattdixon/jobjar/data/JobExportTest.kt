package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobExportTest {
    private val jobs = listOf(
        Job(
            id = 1, title = "Garage", estimatedMinutes = 120, category = "Home", priority = Priority.HIGH, createdAt = 10, costCents = 4000,
            checklist = listOf(ChecklistItem("Bin bags", checked = true), ChecklistItem("Gloves"))
        ),
        Job(id = 2, title = "Sweep", estimatedMinutes = 30, parentId = 1, isDone = true, completedAt = 20, createdAt = 11),
        Job(id = 3, title = "Haul", estimatedMinutes = 45, parentId = 1, dependsOnSubtaskId = 2, scheduledDate = 99, calendarEventId = 7, createdAt = 12),
        Job(
            id = 4, title = "Bins", estimatedMinutes = 5, recurrenceDays = 1, repeatWeekdays = WEEKDAYS_MASK,
            repeatUnit = RepeatUnit.WEEK, repeatAnchor = RepeatAnchor.SCHEDULE, nextDueAt = 50, completionCount = 3, createdAt = 13
        ),
        Job(
            id = 5, title = "Car registration", estimatedMinutes = 20, recurrenceDays = 1, repeatUnit = RepeatUnit.YEAR,
            repeatAnchor = RepeatAnchor.SCHEDULE, repeatMonthDay = 3, repeatMonth = 3, leadDays = 30, nextDueAt = 60, createdAt = 14
        )
    )

    @Test
    fun roundTripKeepsEveryField() {
        val parsed = JobExport.parse(JobExport.write(jobs, exportedAt = 1)) as JobExport.ParseResult.Success
        assertEquals(jobs, parsed.jobs)
    }

    @Test
    fun backupsFromBeforeRepeatUnitsReadTheSameAsBefore() {
        val old = """{"format":"job-jar","version":1,"jobs":[
            {"id":1,"title":"Plants","estimatedMinutes":5,"recurrenceDays":7,"nextDueAt":40},
            {"id":2,"title":"Bins","estimatedMinutes":5,"recurrenceDays":7,"repeatWeekdays":1}
        ]}"""
        val parsed = (JobExport.parse(old) as JobExport.ParseResult.Success).jobs.associateBy { it.id }
        assertEquals(RepeatRule(interval = 7), parsed.getValue(1).repeatRule())
        assertEquals(40L, parsed.getValue(1).nextDueAt)
        assertEquals(RepeatRule(interval = 1, unit = RepeatUnit.WEEK, weekdays = 1), parsed.getValue(2).repeatRule())
    }

    @Test
    fun checklistStorageIsLenient() {
        val items = listOf(ChecklistItem("Bin bags", checked = true), ChecklistItem("Gloves"))
        assertEquals(items, decodeChecklist(encodeChecklist(items)))
        assertEquals(emptyList<ChecklistItem>(), decodeChecklist(""))
        assertEquals(emptyList<ChecklistItem>(), decodeChecklist("not json"))
        assertEquals(listOf(ChecklistItem("Gloves")), decodeChecklist("""[{"text":"  "},{"text":"Gloves"},42]"""))
    }

    @Test
    fun rejectsFilesThatArentExports() {
        assertEquals(JobExport.ParseResult.Failure(JobExport.Reason.NOT_A_BACKUP), JobExport.parse("not json"))
        assertEquals(JobExport.ParseResult.Failure(JobExport.Reason.NOT_A_BACKUP), JobExport.parse("""{"jobs":[]}"""))
        assertEquals(
            JobExport.ParseResult.Failure(JobExport.Reason.NEWER_VERSION),
            JobExport.parse("""{"format":"job-jar","version":99,"jobs":[]}""")
        )
        assertEquals(
            JobExport.ParseResult.Failure(JobExport.Reason.CORRUPT),
            JobExport.parse("""{"format":"job-jar","version":1,"jobs":[{"id":1}]}""")
        )
    }

    @Test
    fun repairsRuleBreakingData() {
        val broken = listOf(
            Job(id = 1, title = "Chore", estimatedMinutes = 10, recurrenceDays = 7, isDone = true),
            Job(id = 2, title = "Sub of repeating", estimatedMinutes = 5, parentId = 1),
            Job(id = 3, title = "Orphan", estimatedMinutes = 5, parentId = 42, dependsOnSubtaskId = 2),
            Job(id = 4, title = "Nested", estimatedMinutes = 5, parentId = 2)
        )
        val parsed = (JobExport.parse(JobExport.write(broken, 0)) as JobExport.ParseResult.Success).jobs.associateBy { it.id }
        assertTrue("repeating jobs never persist done", !parsed.getValue(1).isDone)
        assertNull("repeating jobs can't have subtasks", parsed.getValue(2).parentId)
        assertNull(parsed.getValue(3).parentId)
        assertNull("dependencies only on siblings", parsed.getValue(3).dependsOnSubtaskId)
        assertNull("subtasks are one level deep", parsed.getValue(4).parentId)
    }
}
