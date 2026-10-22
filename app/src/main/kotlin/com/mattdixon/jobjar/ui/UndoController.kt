package com.mattdixon.jobjar.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.mattdixon.jobjar.R
import com.mattdixon.jobjar.data.JobRepository
import com.mattdixon.jobjar.data.UndoSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

enum class UndoKind { COMPLETED, DELETED }

data class UndoEvent(
    val kind: UndoKind,
    val title: String,
    val snapshot: UndoSnapshot,
    /** True when the screen that caused it is about to close (deleting from a job's own page),
     * so only the Jobs list - where you land - should show it. */
    val showOnListOnly: Boolean = false
)

/**
 * App-wide "Undo" for completing and deleting jobs. Whichever screen made the change offers the
 * repository's [UndoSnapshot] here; whichever screen is showing picks it up and shows a snackbar
 * (see [UndoSnackbarEffect]). The undo itself runs in [scope] - the whole app's - so leaving the
 * screen right after tapping Undo can't cancel it halfway.
 */
class UndoController(private val repository: JobRepository, private val scope: CoroutineScope) {
    /** For a change that must finish even though its screen is closing (deleting from a job's own page). */
    fun launchInApp(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private val _pending = MutableStateFlow<UndoEvent?>(null)
    val pending: StateFlow<UndoEvent?> = _pending.asStateFlow()

    fun offer(event: UndoEvent) {
        _pending.value = event
    }

    fun consume(event: UndoEvent) {
        _pending.compareAndSet(event, null)
    }

    fun undo(event: UndoEvent) {
        scope.launch { repository.undo(event.snapshot) }
    }
}

val LocalUndoController = staticCompositionLocalOf<UndoController> { error("No UndoController provided") }

/** Shows pending undo offers in [snackbarHostState]. [isJobList] marks the Jobs list, the one
 * screen that also shows offers from a screen that has just closed. */
@Composable
fun UndoSnackbarEffect(snackbarHostState: SnackbarHostState, isJobList: Boolean = false) {
    val controller = LocalUndoController.current
    val completedFormat = stringResource(R.string.snackbar_completed)
    val deletedFormat = stringResource(R.string.snackbar_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(controller, snackbarHostState, isJobList) {
        controller.pending.filterNotNull().collectLatest { event ->
            if (event.showOnListOnly && !isJobList) return@collectLatest
            controller.consume(event)
            val format = if (event.kind == UndoKind.COMPLETED) completedFormat else deletedFormat
            val result = snackbarHostState.showSnackbar(
                message = String.format(format, event.title),
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) controller.undo(event)
        }
    }
}
