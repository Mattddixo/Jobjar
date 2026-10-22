package com.mattdixon.jobjar.ui.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.Priority
import com.mattdixon.jobjar.data.weekdayBit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

data class AddEditFormState(
    val id: Long? = null,
    /** This job's own parent, once known - null means "not a subtask" (or not yet loaded). */
    val parentId: Long? = null,
    val title: String = "",
    val notes: String = "",
    val estimatedMinutes: Int = 15,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    /** Null = one-off job. Non-null = repeats (every this many days, unless [repeatWeekdays] is set). */
    val recurrenceDays: Int? = null,
    /** Non-null = repeats on these weekdays instead of every N days - see [Job.repeatWeekdays]. */
    val repeatWeekdays: Int? = null,
    /** Only meaningful when [parentId] != null: the sibling subtask that must be done first, if any. */
    val dependsOnSubtaskId: Long? = null,
    val isSaved: Boolean = false,
    /** True once we're sure [parentId] reflects reality - false only while an existing job is still loading. */
    val isLoaded: Boolean = false
) {
    val repeatsOnWeekdays: Boolean get() = recurrenceDays != null && repeatWeekdays != null

    val isValid: Boolean
        get() = title.isNotBlank() && estimatedMinutes > 0 && when {
            recurrenceDays == null -> true
            repeatWeekdays != null -> repeatWeekdays != 0
            else -> recurrenceDays > 0
        }
}

/**
 * The form lives in [savedState] as well as in memory, so a half-typed job survives Android
 * closing the app in the background (switching away to check something, then coming back).
 */
class AddEditJobViewModel(
    private val repository: JobRepository,
    private val jobId: Long?,
    private val parentId: Long?,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val _formState = MutableStateFlow(
        restore() ?: AddEditFormState(id = jobId, parentId = parentId, isLoaded = jobId == null)
    )
    val formState: StateFlow<AddEditFormState> = _formState.asStateFlow()

    init {
        val restored = savedState.contains(KEY_LOADED)
        if (!restored && jobId != null) {
            viewModelScope.launch {
                val job = repository.jobById(jobId).first()
                update {
                    if (job != null) {
                        AddEditFormState(
                            id = job.id,
                            parentId = job.parentId,
                            title = job.title,
                            notes = job.notes,
                            estimatedMinutes = job.estimatedMinutes,
                            category = job.category,
                            priority = job.priority,
                            recurrenceDays = job.recurrenceDays,
                            repeatWeekdays = job.repeatWeekdays,
                            dependsOnSubtaskId = job.dependsOnSubtaskId,
                            isLoaded = true
                        )
                    } else {
                        it.copy(isLoaded = true)
                    }
                }
            }
        } else if (!restored && parentId != null) {
            // Prefill the new subtask's category from its parent, purely as a convenience.
            viewModelScope.launch {
                val parent = repository.jobById(parentId).first()
                if (parent != null && parent.category.isNotBlank() && _formState.value.category.isBlank()) {
                    update { it.copy(category = parent.category) }
                }
            }
        }
    }

    private fun update(transform: (AddEditFormState) -> AddEditFormState) {
        val next = transform(_formState.value)
        _formState.value = next
        savedState[KEY_ID] = next.id
        savedState[KEY_PARENT_ID] = next.parentId
        savedState[KEY_TITLE] = next.title
        savedState[KEY_NOTES] = next.notes
        savedState[KEY_MINUTES] = next.estimatedMinutes
        savedState[KEY_CATEGORY] = next.category
        savedState[KEY_PRIORITY] = next.priority.name
        savedState[KEY_RECURRENCE_DAYS] = next.recurrenceDays
        savedState[KEY_WEEKDAYS] = next.repeatWeekdays
        savedState[KEY_DEPENDS_ON] = next.dependsOnSubtaskId
        savedState[KEY_LOADED] = next.isLoaded
    }

    private fun restore(): AddEditFormState? {
        if (!savedState.contains(KEY_LOADED)) return null
        return AddEditFormState(
            id = savedState[KEY_ID],
            parentId = savedState[KEY_PARENT_ID],
            title = savedState[KEY_TITLE] ?: "",
            notes = savedState[KEY_NOTES] ?: "",
            estimatedMinutes = savedState[KEY_MINUTES] ?: 15,
            category = savedState[KEY_CATEGORY] ?: "",
            priority = Priority.entries.find { it.name == savedState.get<String>(KEY_PRIORITY) } ?: Priority.NORMAL,
            recurrenceDays = savedState[KEY_RECURRENCE_DAYS],
            repeatWeekdays = savedState[KEY_WEEKDAYS],
            dependsOnSubtaskId = savedState[KEY_DEPENDS_ON],
            isLoaded = savedState[KEY_LOADED] ?: true
        )
    }

    fun setTitle(value: String) = update { it.copy(title = value) }
    fun setNotes(value: String) = update { it.copy(notes = value) }
    fun setEstimatedMinutes(value: Int) = update { it.copy(estimatedMinutes = value) }
    fun setCategory(value: String) = update { it.copy(category = value) }
    fun setPriority(value: Priority) = update { it.copy(priority = value) }

    /** Toggles repeating on/off. Turning it on defaults to weekly unless an interval was already set. */
    fun setRecurring(enabled: Boolean) = update {
        it.copy(
            recurrenceDays = if (enabled) it.recurrenceDays ?: 7 else null,
            repeatWeekdays = if (enabled) it.repeatWeekdays else null
        )
    }

    fun setRecurrenceDays(days: Int) = update { it.copy(recurrenceDays = days) }

    /**
     * Switches between "every N days after I finish it" and "on these days of the week".
     * Switching to weekdays starts from today's day, so there's always at least one picked.
     */
    fun setRepeatOnWeekdays(onWeekdays: Boolean) = update {
        if (onWeekdays) {
            it.copy(recurrenceDays = 7, repeatWeekdays = it.repeatWeekdays ?: weekdayBit(LocalDate.now().dayOfWeek))
        } else {
            it.copy(recurrenceDays = it.recurrenceDays ?: 7, repeatWeekdays = null)
        }
    }

    fun toggleWeekday(day: DayOfWeek) = update {
        it.copy(repeatWeekdays = (it.repeatWeekdays ?: 0) xor weekdayBit(day))
    }

    fun setWeekdays(mask: Int) = update { it.copy(repeatWeekdays = mask) }

    fun setDependsOn(id: Long?) = update { it.copy(dependsOnSubtaskId = id) }

    fun save() {
        if (!_formState.value.isValid) return
        viewModelScope.launch {
            persist()
            update { it.copy(isSaved = true) }
        }
    }

    /**
     * Ensures this job already exists in the database - inserting it now, using whatever's
     * currently in the form, if it's still an unsaved draft - then invokes [onReady] with its
     * id. This is what lets "Add subtask" work before you've explicitly hit Save: the job
     * behind the form quietly becomes real the moment you need to attach something to it. An
     * already-saved job is re-saved from the form too, so e.g. having just switched Repeat off
     * is actually stored before a subtask is attached (a repeating job can't have subtasks).
     */
    fun ensurePersisted(onReady: (Long) -> Unit) {
        if (!_formState.value.isValid) return
        viewModelScope.launch {
            onReady(persist())
        }
    }

    private suspend fun persist(): Long {
        val state = _formState.value
        val job = Job(
            id = state.id ?: 0,
            title = state.title.trim(),
            notes = state.notes.trim(),
            estimatedMinutes = state.estimatedMinutes,
            category = state.category.trim(),
            priority = state.priority,
            parentId = state.parentId ?: parentId,
            recurrenceDays = state.recurrenceDays,
            repeatWeekdays = state.repeatWeekdays,
            dependsOnSubtaskId = state.dependsOnSubtaskId
        )
        if (state.id == null) {
            val newId = repository.addJob(job)
            update { it.copy(id = newId) }
            return newId
        }
        // The repository applies just the form's fields onto the job's current row.
        repository.updateJob(job)
        return state.id
    }

    class Factory(
        private val repository: JobRepository,
        private val jobId: Long?,
        private val parentId: Long? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            return AddEditJobViewModel(repository, jobId, parentId, extras.createSavedStateHandle()) as T
        }
    }

    private companion object {
        const val KEY_ID = "form_id"
        const val KEY_PARENT_ID = "form_parent_id"
        const val KEY_TITLE = "form_title"
        const val KEY_NOTES = "form_notes"
        const val KEY_MINUTES = "form_minutes"
        const val KEY_CATEGORY = "form_category"
        const val KEY_PRIORITY = "form_priority"
        const val KEY_RECURRENCE_DAYS = "form_recurrence_days"
        const val KEY_WEEKDAYS = "form_weekdays"
        const val KEY_DEPENDS_ON = "form_depends_on"
        const val KEY_LOADED = "form_loaded"
    }
}
