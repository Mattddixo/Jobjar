package com.mattdixon.jobjar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Job::class], version = 9, exportSchema = true)
@TypeConverters(Converters::class)
abstract class JobDatabase : RoomDatabase() {

    abstract fun jobDao(): JobDao

    companion object {
        @Volatile
        private var INSTANCE: JobDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN parentId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_parentId ON jobs(parentId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN recurrenceDays INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN nextDueAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN completionCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN dependsOnSubtaskId INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN isInProgress INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN scheduledDate INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN calendarEventId INTEGER DEFAULT NULL")
            }
        }

        /**
         * Versions 7 and 8 only ever shipped on the Home Jobs Tracker interop branch, which added
         * a tracker-link column (spawnedFromTrackerJobId, later renamed linkedTrackerJobId). This
         * standalone build has no such column, so any of 6/7/8 lands on the same v9 shape:
         *
         * 1. Repeating jobs can no longer have subtasks (a subtask's own completion never lined up
         *    with its parent's cycle). Any existing ones are kept, just detached into ordinary
         *    top-level jobs, rather than deleted.
         * 2. The jobs table is rebuilt without the tracker column. SQLite's DROP COLUMN needs a
         *    newer engine than minSdk 26 guarantees, so this copies into a fresh table instead -
         *    which also works from v6, where the column never existed.
         */
        private fun migrateToStandalone(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE jobs SET parentId = NULL, dependsOnSubtaskId = NULL " +
                    "WHERE parentId IN (SELECT id FROM jobs WHERE recurrenceDays IS NOT NULL)"
            )
            db.execSQL(
                """
                CREATE TABLE jobs_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    estimatedMinutes INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    isDone INTEGER NOT NULL,
                    isInProgress INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    completedAt INTEGER,
                    timesDrawn INTEGER NOT NULL,
                    parentId INTEGER,
                    recurrenceDays INTEGER,
                    nextDueAt INTEGER,
                    completionCount INTEGER NOT NULL,
                    dependsOnSubtaskId INTEGER,
                    scheduledDate INTEGER,
                    calendarEventId INTEGER
                )
                """.trimIndent()
            )
            val columns = "id, title, notes, estimatedMinutes, category, priority, isDone, isInProgress, " +
                "createdAt, completedAt, timesDrawn, parentId, recurrenceDays, nextDueAt, completionCount, " +
                "dependsOnSubtaskId, scheduledDate, calendarEventId"
            db.execSQL("INSERT INTO jobs_new ($columns) SELECT $columns FROM jobs")
            db.execSQL("DROP TABLE jobs")
            db.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_parentId ON jobs(parentId)")
        }

        val MIGRATION_6_9 = object : Migration(6, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateToStandalone(db)
        }

        val MIGRATION_7_9 = object : Migration(7, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateToStandalone(db)
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateToStandalone(db)
        }

        fun getInstance(context: Context): JobDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JobDatabase::class.java,
                    "jobjar.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_9,
                    MIGRATION_7_9, MIGRATION_8_9
                ).build().also { INSTANCE = it }
            }
    }
}
