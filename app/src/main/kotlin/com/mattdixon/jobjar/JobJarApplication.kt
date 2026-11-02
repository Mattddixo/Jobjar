package com.mattdixon.jobjar

import android.app.Application
import com.mattdixon.jobjar.data.CalendarScheduler
import com.mattdixon.jobjar.data.JobDatabase
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.RoomTransactionRunner
import com.mattdixon.jobjar.data.ThemePreferences
import com.mattdixon.jobjar.reminders.ReminderScheduler
import com.mattdixon.jobjar.widgets.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class JobJarApplication : Application() {
    private val database: JobDatabase by lazy { JobDatabase.getInstance(this) }
    val repository: JobRepository by lazy {
        JobRepository(database.jobDao(), database.completionDao(), CalendarScheduler(this), RoomTransactionRunner(database))
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Covers people who picked a theme before the start-up screen followed it.
        ThemePreferences.applyToSystem(this)
        appScope.launch { repository.mergeCategoryCase() }
        ReminderScheduler.schedule(this)
        // Keeps home screen widgets in step with the jobs, a moment after the last change.
        appScope.launch {
            repository.allJobsFlat.drop(1).collectLatest {
                delay(1_000)
                WidgetUpdater.refresh(this@JobJarApplication)
            }
        }
    }
}
