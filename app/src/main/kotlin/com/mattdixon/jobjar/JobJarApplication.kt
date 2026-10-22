package com.mattdixon.jobjar

import android.app.Application
import com.mattdixon.jobjar.data.CalendarScheduler
import com.mattdixon.jobjar.data.JobDatabase
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.RoomTransactionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JobJarApplication : Application() {
    private val database: JobDatabase by lazy { JobDatabase.getInstance(this) }
    val repository: JobRepository by lazy {
        JobRepository(database.jobDao(), CalendarScheduler(this), RoomTransactionRunner(database))
    }

    /** For app-wide housekeeping that shouldn't be tied to any one screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repository.mergeCategoryCase() }
    }
}
