package com.mattdixon.jobjar.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = InkPrimaryLight,
    onPrimary = InkOnPrimaryLight,
    primaryContainer = InkContainerLight,
    onPrimaryContainer = InkOnContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = SlateOnContainerLight,
    tertiary = TaupeTertiaryLight,
    onTertiary = TaupeOnTertiaryLight,
    tertiaryContainer = TaupeContainerLight,
    onTertiaryContainer = TaupeOnContainerLight,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnBackgroundLight,
    surface = NeutralBackgroundLight,
    onSurface = NeutralOnBackgroundLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    surfaceContainerLowest = NeutralBackgroundLight,
    surfaceContainerLow = NeutralSurfaceContainerLowLight,
    surfaceContainer = NeutralSurfaceContainerLight,
    surfaceContainerHigh = NeutralSurfaceContainerHighLight,
    surfaceContainerHighest = NeutralSurfaceContainerHighestLight,
    outline = NeutralOutlineLight,
    outlineVariant = NeutralOutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val DarkColorScheme = darkColorScheme(
    primary = InkPrimaryDark,
    onPrimary = InkOnPrimaryDark,
    primaryContainer = InkContainerDark,
    onPrimaryContainer = InkOnContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = SlateOnContainerDark,
    tertiary = TaupeTertiaryDark,
    onTertiary = TaupeOnTertiaryDark,
    tertiaryContainer = TaupeContainerDark,
    onTertiaryContainer = TaupeOnContainerDark,
    background = NeutralBackgroundDark,
    onBackground = NeutralOnBackgroundDark,
    surface = NeutralBackgroundDark,
    onSurface = NeutralOnBackgroundDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = NeutralOnSurfaceVariantDark,
    surfaceContainerLowest = NeutralSurfaceContainerLowestDark,
    surfaceContainerLow = NeutralSurfaceContainerLowDark,
    surfaceContainer = NeutralSurfaceContainerDark,
    surfaceContainerHigh = NeutralSurfaceContainerHighDark,
    surfaceContainerHighest = NeutralSurfaceContainerHighestDark,
    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineVariantDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

@Composable
fun JobJarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Android 12+'s dynamic color pulls the whole palette from the phone's
    // wallpaper, silently discarding every color chosen above. This app has a real designed
    // palette (light and dark both), so it should actually be the one that renders.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
