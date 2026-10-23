package com.mattdixon.jobjar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Corner radii. Use these rather than inline RoundedCornerShape(N.dp). */
object AppShapes {
    /** Small buttons. */
    val control = RoundedCornerShape(10.dp)

    /** List and result cards. */
    val card = RoundedCornerShape(14.dp)

    /** The main button on a screen. */
    val action = RoundedCornerShape(16.dp)

    /** Panels and sheets. */
    val panel = RoundedCornerShape(20.dp)

    /** Badges and chips. */
    val pill = RoundedCornerShape(50)

    /** Thin bars. */
    val hairline = RoundedCornerShape(3.dp)
}
