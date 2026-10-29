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
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.withRepeatRule
import com.mattdixon.jobjar.util.CostInput
import com.mattdixon.jobjar.util.costInputText
import com.mattdixon.jobjar.util.parseCostInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

data class AddEditFormState(
    val id: Long? = null,
    /** Null until loaded, or if it isn't a subtask. */
    val parentId: Long? = null,
    val title: String = "",
    val notes: String = "",
    val estimatedMinutes: Int = 15,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    /** As typed. Blank = no cost. */
    val costText: String = "",
    val repeat: RepeatForm = RepeatForm(),
    /** Subtasks only. */
    val dependsOnSubtaskId: Long? = null,
    val isSaved: Boolean = false,
    /** False while an existing job is still loading. */
    val isLoaded: Boolean = false
) {
    val repeats: Boolean get() = repeat.repeats

    val costInput: CostInput get() = parseCostInput(costText)

    val isValid: Boolean
        get() = title.isNotBlank() && estimatedMinutes > 0 && costInput != CostInput.Invalid && repeat.isValid
}

/** Form state is mirrored into [savedState] so a half-typed job survives the app being killed in the background. */
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
                            costText = costInputText(job.costCents),
                            repeat = RepeatForm.from(job),
                            dependsOnSubtaskId = job.dependsOnSubtaskId,
                            isLoaded = true
                        )
                    } else {
                        it.copy(isLoaded = true)
                    }
                }
            }
        } else if (!restored && parentId != null) {
            // Handy default: same category as the parent.
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
        savedState[KEY_COST] = next.costText
        val repeat = next.repeat
        savedState[KEY_REPEAT_INTERVAL] = repeat.interval
        savedState[KEY_REPEAT_UNIT] = repeat.unit.name
        savedState[KEY_REPEAT_ANCHOR] = repeat.anchor.name
        savedState[KEY_WEEKDAYS] = repeat.weekdays
        savedState[KEY_REPEAT_MONTH_DAY] = repeat.monthOption?.monthDay
        savedState[KEY_REPEAT_WEEK_OF_MONTH] = repeat.monthOption?.weekOfMonth
        savedState[KEY_REPEAT_MONTH_WEEKDAY] = repeat.monthOption?.weekday?.value
        savedState[KEY_REPEAT_MONTH] = repeat.month
        savedState[KEY_REPEAT_YEAR_DAY] = repeat.yearDay
        savedState[KEY_LEAD_DAYS] = repeat.leadDays
        savedState[KEY_LEAD_CHOSEN] = repeat.leadChosen
        savedState[KEY_START_DAY] = repeat.startDay
        savedState[KEY_START_PICKED] = repeat.startPicked
        savedState[KEY_ORIGINAL_RULE] = RepeatForm.encodeRule(repeat.originalRule)
        savedState[KEY_ORIGINAL_DUE] = repeat.originalNextDue
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
            costText = savedState[KEY_COST] ?: "",
            repeat = RepeatForm(
                interval = savedState[KEY_REPEAT_INTERVAL],
                unit = RepeatUnit.entries.find { it.name == savedState.get<String>(KEY_REPEAT_UNIT) } ?: RepeatUnit.WEEK,
                anchor = RepeatAnchor.entries.find { it.name == savedState.get<String>(KEY_REPEAT_ANCHOR) } ?: RepeatAnchor.SCHEDULE,
                weekdays = savedState[KEY_WEEKDAYS] ?: 0,
                monthOption = restoreMonthOption(),
                month = savedState[KEY_REPEAT_MONTH],
                yearDay = savedState[KEY_REPEAT_YEAR_DAY],
                leadDays = savedState[KEY_LEAD_DAYS] ?: 0,
                leadChosen = savedState[KEY_LEAD_CHOSEN] ?: false,
                startDay = savedState[KEY_START_DAY] ?: LocalDate.now().toEpochDay(),
                startPicked = savedState[KEY_START_PICKED] ?: false,
                originalRule = RepeatForm.decodeRule(savedState[KEY_ORIGINAL_RULE]),
                originalNextDue = savedState[KEY_ORIGINAL_DUE]
            ),
            dependsOnSubtaskId = savedState[KEY_DEPENDS_ON],
            isLoaded = savedState[KEY_LOADED] ?: true
        )
    }

    fun setTitle(value: String) = update { it.copy(title = value) }
    fun setNotes(value: String) = update { it.copy(notes = value) }
    fun setEstimatedMinutes(value: Int) = update { it.copy(estimatedMinutes = value) }
    fun setCategory(value: String) = update { it.copy(category = value) }
    fun setPriority(value: Priority) = update { it.copy(priority = value) }
    fun setCostText(value: String) = update { it.copy(costText = value) }

    private fun restoreMonthOption(): MonthOption? {
        val day: Int? = savedState[KEY_REPEAT_MONTH_DAY]
        val week: Int? = savedState[KEY_REPEAT_WEEK_OF_MONTH]
        val weekday: Int? = savedState[KEY_REPEAT_MONTH_WEEKDAY]
        return when {
            week != null && weekday != null -> MonthOption(weekOfMonth = week, weekday = DayOfWeek.of(weekday))
            day != null -> MonthOption(monthDay = day)
            else -> null
        }
    }

    /** Repeat on (weekly from today) or off. */
    fun setRecurring(enabled: Boolean) = updateRepeat { if (enabled) it.turnedOn() else it.turnedOff() }

    fun updateRepeat(change: (RepeatForm) -> RepeatForm) = update { it.copy(repeat = change(it.repeat)) }

    fun setDependsOn(id: Long?) = update { it.copy(dependsOnSubtaskId = id) }

    fun save() {
        if (!_formState.value.isValid) return
        viewModelScope.launch {
            persist()
            update { it.copy(isSaved = true) }
        }
    }

    /**
     * Saves the form (inserting it if it's new) and then hands back the id. Used by "Add subtask"
     * so you don't have to hit Save first.
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
            costCents = (state.costInput as? CostInput.Valid)?.cents,
            parentId = state.parentId ?: parentId,
            dependsOnSubtaskId = state.dependsOnSubtaskId
        ).withRepeatRule(state.repeat.rule)
        val firstDue = state.repeat.firstDueToSave()
        if (state.id == null) {
            val newId = repository.addJob(job, firstDue)
            update { it.copy(id = newId) }
            return newId
        }
        repository.updateJob(job, firstDue)
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
        const val KEY_COST = "form_cost"
        const val KEY_REPEAT_INTERVAL = "form_repeat_interval"
        const val KEY_REPEAT_UNIT = "form_repeat_unit"
        const val KEY_REPEAT_ANCHOR = "form_repeat_anchor"
        const val KEY_WEEKDAYS = "form_weekdays"
        const val KEY_REPEAT_MONTH_DAY = "form_repeat_month_day"
        const val KEY_REPEAT_WEEK_OF_MONTH = "form_repeat_week_of_month"
        const val KEY_REPEAT_MONTH_WEEKDAY = "form_repeat_month_weekday"
        const val KEY_REPEAT_MONTH = "form_repeat_month"
        const val KEY_LEAD_DAYS = "form_lead_days"
        const val KEY_LEAD_CHOSEN = "form_lead_chosen"
        const val KEY_REPEAT_YEAR_DAY = "form_repeat_year_day"
        const val KEY_START_DAY = "form_start_day"
        const val KEY_START_PICKED = "form_start_picked"
        const val KEY_ORIGINAL_RULE = "form_original_rule"
        const val KEY_ORIGINAL_DUE = "form_original_due"
        const val KEY_DEPENDS_ON = "form_depends_on"
        const val KEY_LOADED = "form_loaded"
    }
}
