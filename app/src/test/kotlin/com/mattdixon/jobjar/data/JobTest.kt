package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTest {
    @Test
    fun pendingForOneOffAndRepeating() {
        assertTrue(Job(title = "a", estimatedMinutes = 5).isPending())
        assertFalse(Job(title = "a", estimatedMinutes = 5, isDone = true).isPending())
        assertTrue(Job(title = "a", estimatedMinutes = 5, recurrenceDays = 1, nextDueAt = 100).isPending(now = 100))
        assertFalse(Job(title = "a", estimatedMinutes = 5, recurrenceDays = 1, nextDueAt = 101).isPending(now = 100))
    }

    @Test
    fun scheduledOrStartedJobsAreNotDrawable() {
        assertFalse(Job(title = "a", estimatedMinutes = 5, scheduledDate = 1).isAvailableToDraw())
        assertFalse(Job(title = "a", estimatedMinutes = 5, isInProgress = true).isAvailableToDraw())
    }

    @Test
    fun remainingMinutesOnlyCountsFinishedSubtasks() {
        val subtasks = listOf(
            Job(title = "s1", estimatedMinutes = 20, isDone = true),
            Job(title = "s2", estimatedMinutes = 30)
        )
        assertEquals(40, remainingMinutesOf(60, subtasks))
    }

    @Test
    fun dependencyPickerExcludesCycles() {
        val a = Job(id = 1, title = "a", estimatedMinutes = 5, parentId = 9)
        val b = Job(id = 2, title = "b", estimatedMinutes = 5, parentId = 9, dependsOnSubtaskId = 1)
        val c = Job(id = 3, title = "c", estimatedMinutes = 5, parentId = 9, dependsOnSubtaskId = 2)
        // Editing a: b and c both (transitively) depend on a, so picking either would loop.
        assertEquals(emptyList<Job>(), subtasksAvailableAsDependency(listOf(a, b, c), excludingSelfId = 1))
        assertEquals(listOf(a, b), subtasksAvailableAsDependency(listOf(a, b, c), excludingSelfId = 3))
    }
}
