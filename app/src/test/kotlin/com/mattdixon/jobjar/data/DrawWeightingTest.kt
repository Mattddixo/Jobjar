package com.mattdixon.jobjar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrawWeightingTest {
    @Test
    fun aJobOnItsFairShareWeighsMost() {
        // 3 hours, 3 jobs wanted: each pick aims for an hour.
        assertEquals(1.0, drawWeight(60, 180, 3), 1e-9)
        assertEquals(0.5, drawWeight(30, 180, 3), 1e-9)
        assertEquals(0.25, drawWeight(15, 180, 3), 1e-9)
        // Longer than the share crowds out the rest of the batch.
        assertEquals((60.0 / 90) * (60.0 / 90), drawWeight(90, 180, 3), 1e-9)
        assertEquals(0.16, drawWeight(150, 180, 3), 1e-9)
    }

    @Test
    fun oneJobOrAllAimsForTheWholeTime() {
        assertEquals(1.0, drawWeight(180, 180, 1), 1e-9)
        assertEquals(0.5, drawWeight(90, 180, 1), 1e-9)
        assertEquals(drawWeight(90, 180, 1), drawWeight(90, 180, null), 1e-9)
    }

    @Test
    fun weightsStayBetweenTheFloorAndOne() {
        assertEquals(MIN_DRAW_WEIGHT, drawWeight(5, 240, 1), 1e-9)
        assertEquals(MIN_DRAW_WEIGHT, drawWeight(0, 240, 1), 1e-9)
        assertEquals(MIN_DRAW_WEIGHT, drawWeight(-20, 240, 1), 1e-9)
        assertEquals(1.0, drawWeight(1, 0, 1), 1e-9)
        assertEquals(1.0, drawWeight(30, 30, 0), 1e-9)
    }

    @Test
    fun pickWeightedFollowsTheWeights() {
        val random = Random(7)
        val counts = IntArray(3)
        repeat(30_000) { counts[pickWeighted(listOf(0, 1, 2), random) { listOf(1.0, 2.0, 7.0)[it] }!!]++ }
        assertEquals(0.1, counts[0] / 30_000.0, 0.01)
        assertEquals(0.2, counts[1] / 30_000.0, 0.01)
        assertEquals(0.7, counts[2] / 30_000.0, 0.01)
        assertNull(pickWeighted(emptyList<Int>(), random) { 1.0 })
    }

    private fun repoWith(dao: FakeJobDao, seed: Int) =
        JobRepository(dao, FakeCalendar(), DirectTransactions, random = Random(seed))

    @Test
    fun oneJobInALongWindowLeansTowardJobsThatUseTheTime() = runTest {
        val dao = FakeJobDao()
        val quick = dao.insert(Job(title = "Quick", estimatedMinutes = 15))
        val long = dao.insert(Job(title = "Long", estimatedMinutes = 120))
        val weighted = repoWith(dao, 1)
        val even = repoWith(dao, 1)
        var longWeighted = 0
        var longEven = 0
        repeat(2_000) {
            if (weighted.drawJob(120, emptySet(), emptyList(), favourFill = true)!!.job.id == long) longWeighted++
            if (even.drawJob(120, emptySet(), emptyList(), favourFill = false)!!.job.id == long) longEven++
        }
        // Weights 1 vs 0.125: the long job should come out about 89% of the time, not 50%.
        assertEquals(0.89, longWeighted / 2_000.0, 0.03)
        assertEquals(0.5, longEven / 2_000.0, 0.04)
        assertTrue("the quick job still turns up", longWeighted < 2_000)
        assertTrue(quick != long)
    }

    @Test
    fun aBatchOfThreeLeansTowardThreeSolidJobsAndNeverRunsOver() = runTest {
        val dao = FakeJobDao()
        repeat(4) { dao.insert(Job(title = "Hour $it", estimatedMinutes = 60)) }
        repeat(6) { dao.insert(Job(title = "Quick $it", estimatedMinutes = 15)) }
        val huge = dao.insert(Job(title = "Huge", estimatedMinutes = 170))
        val repo = repoWith(dao, 3)
        var hugeFirst = 0
        var threeHours = 0
        val runs = 1_000
        repeat(runs) {
            var remaining = 180
            val excluded = mutableListOf<Long>()
            val picks = mutableListOf<DrawPick>()
            while (picks.size < 3) {
                val pick = repo.drawJob(remaining, emptySet(), excluded, picksLeft = 3 - picks.size, favourFill = true) ?: break
                picks += pick
                excluded += pick.job.id
                remaining -= pick.minutesUsed
            }
            assertTrue("never more than the time", picks.sumOf { it.minutesUsed } <= 180)
            if (picks.first().job.id == huge) hugeFirst++
            if (picks.size == 3 && picks.all { it.job.estimatedMinutes == 60 }) threeHours++
        }
        // Even draws would open with the 170-minute job 1 time in 11 and almost never give three
        // hour-long jobs; weighted, the huge one is rare and three hours is common.
        assertTrue("huge first: $hugeFirst", hugeFirst < runs * 0.05)
        assertTrue("three hours: $threeHours", threeHours > runs * 0.25)
    }

    @Test
    fun anOverrunParentNeverGivesTimeBack() = runTest {
        val dao = FakeJobDao()
        val parent = dao.insert(Job(title = "Garage", estimatedMinutes = 30))
        dao.insert(Job(title = "Sweep", estimatedMinutes = 45, parentId = parent, isDone = true, completedAt = 1))
        dao.insert(Job(title = "Bins", estimatedMinutes = 120, parentId = parent))
        val pick = repoWith(dao, 1).drawJob(10, emptySet(), emptyList(), favourFill = true)!!
        assertEquals(parent, pick.job.id)
        assertEquals(0, pick.minutesUsed)
    }
}
