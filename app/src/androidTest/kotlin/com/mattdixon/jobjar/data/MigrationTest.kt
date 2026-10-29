package com.mattdixon.jobjar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds old-version databases by hand (same SQL those versions ran) and opens them with the
 * current schema. Room validates the result, so a bad migration fails here first.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    /** The jobs table as v1 created it, plus every ALTER up to [version]. */
    private fun createLegacyDatabase(version: Int) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, " +
                "notes TEXT NOT NULL, estimatedMinutes INTEGER NOT NULL, category TEXT NOT NULL, " +
                "priority TEXT NOT NULL, isDone INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "completedAt INTEGER, timesDrawn INTEGER NOT NULL)"
        )
        db.execSQL("ALTER TABLE jobs ADD COLUMN parentId INTEGER DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_parentId ON jobs(parentId)")
        db.execSQL("ALTER TABLE jobs ADD COLUMN recurrenceDays INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE jobs ADD COLUMN nextDueAt INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE jobs ADD COLUMN completionCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE jobs ADD COLUMN dependsOnSubtaskId INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE jobs ADD COLUMN isInProgress INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE jobs ADD COLUMN scheduledDate INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE jobs ADD COLUMN calendarEventId INTEGER DEFAULT NULL")
        // Only version 8 had the tracker link; 9 dropped it again.
        if (version == 8) db.execSQL("ALTER TABLE jobs ADD COLUMN linkedTrackerJobId INTEGER DEFAULT NULL")
        if (version >= 10) db.execSQL("ALTER TABLE jobs ADD COLUMN repeatWeekdays INTEGER DEFAULT NULL")
        if (version >= 11) db.execSQL("ALTER TABLE jobs ADD COLUMN costCents INTEGER DEFAULT NULL")
        if (version >= 12) db.execSQL("ALTER TABLE jobs ADD COLUMN checklist TEXT NOT NULL DEFAULT '[]'")

        val insert = "INSERT INTO jobs (id, title, notes, estimatedMinutes, category, priority, isDone, createdAt, " +
            "timesDrawn, parentId, recurrenceDays, dependsOnSubtaskId) VALUES (?, ?, '', ?, 'Home', 'NORMAL', ?, 1, 0, ?, ?, ?)"
        db.execSQL(insert, arrayOf<Any?>(1, "Water plants", 10, 0, null, 7, null))
        db.execSQL(insert, arrayOf<Any?>(2, "Fern", 5, 1, 1, null, null))
        db.execSQL(insert, arrayOf<Any?>(3, "Garage", 120, 0, null, null, null))
        db.execSQL(insert, arrayOf<Any?>(4, "Sort", 30, 0, 3, null, null))
        db.execSQL(insert, arrayOf<Any?>(5, "Haul", 30, 0, 3, null, 4))
        db.version = version
        db.close()
    }

    private fun openCurrent(): JobDatabase =
        Room.databaseBuilder(context, JobDatabase::class.java, name)
            .addMigrations(*JobDatabase.ALL_MIGRATIONS)
            .build()

    @Test
    fun upgradesFromVersion8() = runTest { checkUpgraded(8) }

    @Test
    fun upgradesFromVersion6() = runTest { checkUpgraded(6) }

    @Test
    fun upgradesFromVersion10() = runTest { checkRecentUpgrade(10) }

    @Test
    fun upgradesFromVersion11() = runTest { checkRecentUpgrade(11) }

    @Test
    fun upgradesFromVersion12() = runTest { checkRecentUpgrade(12) }

    /** Versions after the standalone rework only add columns. */
    private suspend fun checkRecentUpgrade(fromVersion: Int) {
        createLegacyDatabase(fromVersion)
        val db = openCurrent()
        try {
            val jobs = db.jobDao().getAllJobs()
            assertEquals(5, jobs.size)
            assertTrue("nobody has a cost yet", jobs.all { it.costCents == null })
            assertTrue("or a checklist", jobs.all { it.checklist.isEmpty() })
            val chore = jobs.single { it.id == 1L }
            assertEquals("old repeats still count days", RepeatUnit.DAY, chore.repeatUnit)
            assertEquals(RepeatAnchor.COMPLETION, chore.repeatAnchor)
            assertEquals(0, chore.leadDays)
            assertEquals(RepeatRule(interval = 7), chore.repeatRule())
        } finally {
            db.close()
        }
    }

    private suspend fun checkUpgraded(fromVersion: Int) {
        createLegacyDatabase(fromVersion)
        val db = openCurrent()
        try {
            val jobs = db.jobDao().getAllJobs().associateBy { it.id }
            assertEquals(5, jobs.size)
            assertNull("subtasks of a repeating job are detached", jobs.getValue(2).parentId)
            assertTrue("...but kept, done state and all", jobs.getValue(2).isDone)
            assertEquals("one-off subtasks are untouched", 3L, jobs.getValue(4).parentId)
            assertEquals(4L, jobs.getValue(5).dependsOnSubtaskId)
            assertNull(jobs.getValue(1).repeatWeekdays)

            val columns = db.openHelper.readableDatabase.query("PRAGMA table_info(jobs)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertFalse("tracker link column is gone", "linkedTrackerJobId" in columns)
            assertTrue("repeatWeekdays", "repeatWeekdays" in columns)
            assertTrue("costCents", "costCents" in columns)
            assertTrue("checklist", "checklist" in columns)
            assertTrue("repeatUnit", "repeatUnit" in columns)
            assertEquals(RepeatRule(interval = 7), jobs.getValue(1).repeatRule())
        } finally {
            db.close()
        }
    }
}
