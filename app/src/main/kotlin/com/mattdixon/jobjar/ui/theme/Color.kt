package com.mattdixon.jobjar.ui.theme

import androidx.compose.ui.graphics.Color

// A warm, hand-built palette instead of Material's neutral-grey baseline (see Theme.kt for why
// dynamicColor is off - without that, every one of these roles gets silently overridden by
// colors pulled from the phone's wallpaper instead, which is what made the app look grey no
// matter what was defined here).
//
// Three families, each with an intentional job:
//   - Amber/marigold (primary)  - the jar itself: the main actions (Draw, Save, filters).
//   - Jade/teal (secondary)     - growth and progress: badges, category tags, done states.
//   - Raspberry/coral (tertiary) - the one accent reserved for "this is the reward" moments,
//     namely a drawn job's card - nothing else in the app uses it, so it stays special.
// Neutrals lean warm (a hint of parchment/cream) rather than cold grey, in both themes.

// Amber / marigold - primary
val AmberPrimaryLight = Color(0xFFA85D00)
val AmberOnPrimaryLight = Color(0xFFFFFFFF)
val AmberContainerLight = Color(0xFFFFDDB0)
val AmberOnContainerLight = Color(0xFF3E2500)

val AmberPrimaryDark = Color(0xFFFFB870)
val AmberOnPrimaryDark = Color(0xFF4A2800)
val AmberContainerDark = Color(0xFF6E3E00)
val AmberOnContainerDark = Color(0xFFFFDDB0)

// Jade / teal - secondary
val JadeSecondaryLight = Color(0xFF3C6E5B)
val JadeOnSecondaryLight = Color(0xFFFFFFFF)
val JadeContainerLight = Color(0xFFC9F0DD)
val JadeOnContainerLight = Color(0xFF06201A)

val JadeSecondaryDark = Color(0xFF9FD5BF)
val JadeOnSecondaryDark = Color(0xFF08372A)
val JadeContainerDark = Color(0xFF23503F)
val JadeOnContainerDark = Color(0xFFC9F0DD)

// Raspberry / coral - tertiary, reserved for the drawn-job spotlight
val CoralTertiaryLight = Color(0xFFA02351)
val CoralOnTertiaryLight = Color(0xFFFFFFFF)
val CoralContainerLight = Color(0xFFFFD9E3)
val CoralOnContainerLight = Color(0xFF3F0018)

val CoralTertiaryDark = Color(0xFFFFB1C8)
val CoralOnTertiaryDark = Color(0xFF5C1133)
val CoralContainerDark = Color(0xFF7D1D46)
val CoralOnContainerDark = Color(0xFFFFD9E3)

// Warm neutrals - background/surface family
val WarmBackgroundLight = Color(0xFFFFFBF5)
val WarmOnBackgroundLight = Color(0xFF201B13)
val WarmSurfaceVariantLight = Color(0xFFF1E4D2)
val WarmOnSurfaceVariantLight = Color(0xFF504639)
val WarmSurfaceContainerLowLight = Color(0xFFFBF3E7)
val WarmSurfaceContainerLight = Color(0xFFF6EEE0)
val WarmSurfaceContainerHighLight = Color(0xFFF0E7D6)
val WarmOutlineLight = Color(0xFF837568)
val WarmOutlineVariantLight = Color(0xFFD5C6B2)

val WarmBackgroundDark = Color(0xFF17130D)
val WarmOnBackgroundDark = Color(0xFFEAE1D5)
val WarmSurfaceVariantDark = Color(0xFF504636)
val WarmOnSurfaceVariantDark = Color(0xFFD5C6B2)
val WarmSurfaceContainerLowDark = Color(0xFF201B14)
val WarmSurfaceContainerDark = Color(0xFF251F18)
val WarmSurfaceContainerHighDark = Color(0xFF302922)
val WarmOutlineDark = Color(0xFF9E8F7D)
val WarmOutlineVariantDark = Color(0xFF504636)

// Error - kept close to Material's default red family (no reason to reinvent this one)
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
