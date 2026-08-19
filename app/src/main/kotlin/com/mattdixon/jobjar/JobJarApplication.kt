package com.mattdixon.jobjar

import android.app.Application
import com.mattdixon.jobjar.data.JobDatabase
import com.mattdixon.jobjar.data.JobRepository

class JobJarApplication : Application() {
    private val database: JobDatabase by lazy { JobDatabase.getInstance(this) }
    val repository: JobRepository by lazy { JobRepository(database.jobDao(), this) }
}
