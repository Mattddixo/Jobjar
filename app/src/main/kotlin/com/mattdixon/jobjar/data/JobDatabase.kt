package com.mattdixon.jobjar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Job::class], version = 4, exportSchema = true)
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

        fun getInstance(context: Context): JobDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JobDatabase::class.java,
                    "jobjar.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
