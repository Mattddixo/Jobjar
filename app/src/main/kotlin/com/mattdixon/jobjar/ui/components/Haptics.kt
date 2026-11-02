package com.mattdixon.jobjar.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** A solid bump for pulling jobs out of the jar. */
fun View.drawHaptic() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** A crisp tick for finishing something. */
fun View.doneHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )
}
