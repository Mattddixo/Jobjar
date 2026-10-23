package com.mattdixon.jobjar.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobExport
import com.mattdixon.jobjar.data.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DataMessage {
    data class Exported(val count: Int) : DataMessage
    data class Imported(val count: Int) : DataMessage
    data object ExportFailed : DataMessage
    data class ImportFailed(val reason: JobExport.Reason?) : DataMessage
}

/** Parsed and waiting for the user to confirm. */
data class PendingImport(val jobs: List<Job>, val currentCount: Int)

class SettingsViewModel(private val repository: JobRepository) : ViewModel() {
    private val _message = MutableStateFlow<DataMessage?>(null)
    val message: StateFlow<DataMessage?> = _message.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun export(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val jobs = repository.exportJobs()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        it.write(JobExport.write(jobs, System.currentTimeMillis()))
                    } != null
                }.getOrDefault(false)
            }
            _message.value = if (ok) DataMessage.Exported(jobs.size) else DataMessage.ExportFailed
        }
    }

    /** Nothing changes until [confirmImport]. */
    fun readImport(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text == null) {
                _message.value = DataMessage.ImportFailed(null)
                return@launch
            }
            when (val result = withContext(Dispatchers.Default) { JobExport.parse(text) }) {
                is JobExport.ParseResult.Failure -> _message.value = DataMessage.ImportFailed(result.reason)
                is JobExport.ParseResult.Success ->
                    _pendingImport.value = PendingImport(result.jobs, repository.exportJobs().size)
            }
        }
    }

    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            repository.replaceAllJobs(pending.jobs)
            _message.value = DataMessage.Imported(pending.jobs.size)
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    class Factory(private val repository: JobRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
    }
}
