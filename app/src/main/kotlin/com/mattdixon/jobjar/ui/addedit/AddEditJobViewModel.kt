package com.mattdixon.jobjar.ui.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mattdixon.jobjar.data.Job
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.LAST_DAY_OF_MONTH
import com.mattdixon.jobjar.data.Priority
import com.mattdixon.jobjar.data.RepeatAnchor
import com.mattdixon.jobjar.data.RepeatRule
import com.mattdixon.jobjar.data.RepeatUnit
import com.mattdixon.jobjar.data.defaultAnchor
import com.mattdixon.jobjar.data.repeatRule
import com.mattdixon.jobjar.data.suggestedLeadDays
import com.mattdixon.jobjar.data.weekdayBit
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
import java.time.Month

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
    /** Null = doesn't repeat. 0 while the number is being retyped. */
    val repeatInterval: Int? = null,
    val repeatUnit: RepeatUnit = RepeatUnit.WEEK,
    /** Every week only. Null or 0 = no set days (see [Job.repeatWeekdays]). */
    val repeatWeekdays: Int? = null,
    val repeatAnchor: RepeatAnchor = RepeatAnchor.COMPLETION,
    /** Monthly/yearly on a schedule. See [Job.repeatMonthDay]. */
    val repeatMonthDay: Int? = null,
    val repeatMonth: Int? = null,
    val leadDays: Int = 0,
    /** Picked "show it early" by hand, so changing the rule stops suggesting one. */
    val leadChosen: Boolean = false,
    /** Subtasks only. */
    val dependsOnSubtaskId: Long? = null,
    val isSaved: Boolean = false,
    /** False while an existing job is still loading. */
    val isLoaded: Boolean = false
) {
    val repeats: Boolean get() = repeatInterval != null

    /** Set days only make sense for "every 1 week". */
    val canPickWeekdays: Boolean get() = repeatUnit == RepeatUnit.WEEK && repeatInterval == 1

    val rule: RepeatRule?
        get() = repeatInterval?.let { interval ->
            RepeatRule(
                interval = interval,
                unit = repeatUnit,
                weekdays = repeatWeekdays?.takeIf { canPickWeekdays && it != 0 },
                anchor = repeatAnchor,
                monthDay = repeatMonthDay,
                month = repeatMonth,
                leadDays = leadDays
            )
        }

    val costInput: CostInput get() = parseCostInput(costText)

    val isValid: Boolean
        get() = title.isNotBlank() && estimatedMinutes > 0 && costInput != CostInput.Invalid && when {
            repeatInterval == null -> true
            else -> repeatInterval > 0
        }
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
                        val rule = job.repeatRule()
                        AddEditFormState(
                            id = job.id,
                            parentId = job.parentId,
                            title = job.title,
                            notes = job.notes,
                            estimatedMinutes = job.estimatedMinutes,
                            category = job.category,
                            priority = job.priority,
                            costText = costInputText(job.costCents),
                            repeatInterval = rule?.interval,
                            repeatUnit = rule?.unit ?: RepeatUnit.WEEK,
                            repeatWeekdays = rule?.weekdays,
                            repeatAnchor = rule?.anchor ?: RepeatAnchor.COMPLETION,
                            repeatMonthDay = rule?.monthDay,
                            repeatMonth = rule?.month,
                            leadDays = rule?.leadDays ?: 0,
                            leadChosen = rule != null,
                            dependsOnSubtaskId = job.dependsOnSubtaskId,
                            isLoaded = true
                        ).withDateDefaults()
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
        savedState[KEY_REPEAT_INTERVAL] = next.repeatInterval
        savedState[KEY_REPEAT_UNIT] = next.repeatUnit.name
        savedState[KEY_WEEKDAYS] = next.repeatWeekdays
        savedState[KEY_REPEAT_ANCHOR] = next.repeatAnchor.name
        savedState[KEY_REPEAT_MONTH_DAY] = next.repeatMonthDay
        savedState[KEY_REPEAT_MONTH] = next.repeatMonth
        savedState[KEY_LEAD_DAYS] = next.leadDays
        savedState[KEY_LEAD_CHOSEN] = next.leadChosen
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
            repeatInterval = savedState[KEY_REPEAT_INTERVAL],
            repeatUnit = RepeatUnit.entries.find { it.name == savedState.get<String>(KEY_REPEAT_UNIT) } ?: RepeatUnit.WEEK,
            repeatWeekdays = savedState[KEY_WEEKDAYS],
            repeatAnchor = RepeatAnchor.entries.find { it.name == savedState.get<String>(KEY_REPEAT_ANCHOR) } ?: RepeatAnchor.COMPLETION,
            repeatMonthDay = savedState[KEY_REPEAT_MONTH_DAY],
            repeatMonth = savedState[KEY_REPEAT_MONTH],
            leadDays = savedState[KEY_LEAD_DAYS] ?: 0,
            leadChosen = savedState[KEY_LEAD_CHOSEN] ?: false,
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

    /** Defaults to weekly. */
    fun setRecurring(enabled: Boolean) = update {
        if (enabled) {
            if (it.repeats) it else it.withPreset(RepeatPreset.WEEKLY)
        } else {
            it.copy(repeatInterval = null)
        }
    }

    fun applyPreset(preset: RepeatPreset) = update { it.withPreset(preset) }

    fun setRepeatInterval(value: Int) = update { it.copy(repeatInterval = value).withSuggestedLead() }

    /** A new unit gets that unit's usual "counts from" and, for months and years, today's date. */
    fun setRepeatUnit(unit: RepeatUnit) = update {
        if (unit == it.repeatUnit) {
            it
        } else {
            it.copy(repeatUnit = unit, repeatAnchor = defaultAnchor(unit)).withDateDefaults().withSuggestedLead()
        }
    }

    fun setRepeatAnchor(anchor: RepeatAnchor) = update { it.copy(repeatAnchor = anchor).withDateDefaults() }

    fun setRepeatMonthDay(day: Int) = update { it.copy(repeatMonthDay = day) }

    /** Keeps the day valid for the new month: the 31st becomes the 30th in April. Feb 29 is allowed. */
    fun setRepeatMonth(month: Int) = update { state ->
        val maxDay = Month.of(month).maxLength()
        state.copy(repeatMonth = month, repeatMonthDay = state.repeatMonthDay?.let { if (it > maxDay) maxDay else it })
    }

    fun setLeadDays(days: Int) = update { it.copy(leadDays = days, leadChosen = true) }

    fun toggleWeekday(day: DayOfWeek) = update {
        it.copy(repeatWeekdays = (it.repeatWeekdays ?: 0) xor weekdayBit(day)).withSuggestedLead()
    }

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
        ).withRepeatRule(state.rule)
        if (state.id == null) {
            val newId = repository.addJob(job)
            update { it.copy(id = newId) }
            return newId
        }
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
        const val KEY_COST = "form_cost"
        const val KEY_REPEAT_INTERVAL = "form_repeat_interval"
        const val KEY_REPEAT_UNIT = "form_repeat_unit"
        const val KEY_WEEKDAYS = "form_weekdays"
        const val KEY_REPEAT_ANCHOR = "form_repeat_anchor"
        const val KEY_REPEAT_MONTH_DAY = "form_repeat_month_day"
        const val KEY_REPEAT_MONTH = "form_repeat_month"
        const val KEY_LEAD_DAYS = "form_lead_days"
        const val KEY_LEAD_CHOSEN = "form_lead_chosen"
        const val KEY_DEPENDS_ON = "form_depends_on"
        const val KEY_LOADED = "form_loaded"
    }
}

/** The quick picks above the "Every N ..." line. */
enum class RepeatPreset(val interval: Int, val unit: RepeatUnit) {
    DAILY(1, RepeatUnit.DAY),
    WEEKLY(1, RepeatUnit.WEEK),
    FORTNIGHTLY(2, RepeatUnit.WEEK),
    MONTHLY(1, RepeatUnit.MONTH),
    QUARTERLY(3, RepeatUnit.MONTH),
    YEARLY(1, RepeatUnit.YEAR);

    /** "Every 14 days" counts as every 2 weeks, so older jobs still light up their chip. */
    fun matches(state: AddEditFormState): Boolean {
        val interval = state.repeatInterval ?: return false
        if (state.rule?.onWeekdays == true) return false
        if (state.repeatUnit == unit) return interval == this.interval
        return unit == RepeatUnit.WEEK && state.repeatUnit == RepeatUnit.DAY && interval == this.interval * 7
    }
}

private fun AddEditFormState.withPreset(preset: RepeatPreset): AddEditFormState =
    copy(
        repeatInterval = preset.interval,
        repeatUnit = preset.unit,
        repeatWeekdays = null,
        repeatAnchor = defaultAnchor(preset.unit)
    ).withDateDefaults().withSuggestedLead()

/** Monthly and yearly on a schedule need a date: start from today's. */
private fun AddEditFormState.withDateDefaults(): AddEditFormState {
    if (repeatAnchor != RepeatAnchor.SCHEDULE) return this
    val today = LocalDate.now()
    return when (repeatUnit) {
        RepeatUnit.MONTH -> copy(repeatMonthDay = repeatMonthDay ?: today.dayOfMonth)
        RepeatUnit.YEAR -> {
            val month = repeatMonth ?: today.monthValue
            val maxDay = Month.of(month).maxLength()
            // Yearly picks a real date, so "last day" from a monthly rule becomes that month's last.
            val day = when (repeatMonthDay) {
                null -> today.dayOfMonth.coerceAtMost(maxDay)
                LAST_DAY_OF_MONTH -> maxDay
                else -> repeatMonthDay.coerceIn(1, maxDay)
            }
            copy(repeatMonth = month, repeatMonthDay = day)
        }
        else -> this
    }
}

private fun AddEditFormState.withSuggestedLead(): AddEditFormState {
    if (leadChosen) return this
    val current = rule?.takeIf { (repeatInterval ?: 0) > 0 } ?: return this
    return copy(leadDays = suggestedLeadDays(current))
}
