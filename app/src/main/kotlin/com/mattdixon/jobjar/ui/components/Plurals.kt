package com.mattdixon.jobjar.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.pluralStringResource

/** Wraps the opt-in so call sites don't need it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun plural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    pluralStringResource(id, count, *formatArgs)
