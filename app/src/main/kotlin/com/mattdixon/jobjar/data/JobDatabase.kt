package com.mattdixon.jobjar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Job::class], version = 14, exportSchema = true)
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
         * 7 and 8 were only used by the old Job Tracker build, which added a tracker link column.
         * Whichever of 6/7/8 you're on, this gets you to 9:
         *
         * 1. Subtasks of repeating jobs are detached (kept as normal jobs, not deleted).
         * 2. The table is rebuilt without the tracker column. DROP COLUMN isn't available on all the
         *    SQLite versions minSdk 26 ships with, so it's copied into a new table.
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

        /** Weekday repeats. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatWeekdays INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN costCents INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN checklist TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** Repeat units, dates and lead windows. Existing repeats stay "every N days from when you finish". */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatUnit TEXT NOT NULL DEFAULT 'DAY'")
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatAnchor TEXT NOT NULL DEFAULT 'COMPLETION'")
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatMonthDay INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatMonth INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN leadDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Monthly on a weekday ("the first Monday"). */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN repeatWeekOfMonth INTEGER DEFAULT NULL")
            }
        }

        /** Also used by MigrationTest. */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_9, MIGRATION_7_9, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
            MIGRATION_12_13, MIGRATION_13_14
        )

        fun getInstance(context: Context): JobDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JobDatabase::class.java,
                    "jobjar.db"
                ).addMigrations(*ALL_MIGRATIONS).build().also { INSTANCE = it }
            }
    }
}

class RoomTransactionRunner(private val db: RoomDatabase) : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction(block)
}
