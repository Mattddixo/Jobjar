package com.mattdixon.jobjar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTest {
    @Test
    fun totalCostAddsUpTheJobAndItsSubtasks() {
        val parent = Job(id = 1, title = "Garage", estimatedMinutes = 60, costCents = 4000)
        val subtasks = listOf(
            Job(id = 2, title = "Paint", estimatedMinutes = 30, parentId = 1, costCents = 2550),
            Job(id = 3, title = "Sweep", estimatedMinutes = 30, parentId = 1)
        )
        assertEquals(6550L, totalCostCents(parent, subtasks))
        assertEquals(2550L, totalCostCents(parent.copy(costCents = null), subtasks))
        assertNull(totalCostCents(parent.copy(costCents = null), subtasks.map { it.copy(costCents = null) }))
    }

    @Test
    fun pendingForOneOffAndRepeating() {
        assertTrue(Job(title = "a", estimatedMinutes = 5).isPending())
        assertFalse(Job(title = "a", estimatedMinutes = 5, isDone = true).isPending())
        assertTrue(Job(title = "a", estimatedMinutes = 5, recurrenceDays = 1, nextDueAt = 100).isPending(now = 100))
        assertFalse(Job(title = "a", estimatedMinutes = 5, recurrenceDays = 1, nextDueAt = 101).isPending(now = 100))
    }

    @Test
    fun scheduledOrStartedJobsAreNotDrawable() {
        val now = 1_000_000_000L
        assertFalse(Job(title = "a", estimatedMinutes = 5, scheduledDate = now + 60_000).isAvailableToDraw(now))
        assertFalse(Job(title = "a", estimatedMinutes = 5, isInProgress = true).isAvailableToDraw(now))
        // A slot that ended with the job still to do is missed: back in the jar.
        val missed = Job(title = "a", estimatedMinutes = 5, scheduledDate = now - 10 * 60_000)
        assertTrue(missed.isMissed(now))
        assertTrue(missed.isAvailableToDraw(now))
        // Mid-slot is still booked.
        assertTrue(Job(title = "a", estimatedMinutes = 30, scheduledDate = now - 10 * 60_000).isBooked(now))
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
