package com.mattdixon.jobjar.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.pluralStringResource

/** One place for plural strings ("1 subtask" / "3 subtasks"), so the opt-in below isn't repeated at every call site. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun plural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    pluralStringResource(id, count, *formatArgs)
