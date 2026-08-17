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
    primary = AmberPrimaryLight,
    onPrimary = AmberOnPrimaryLight,
    primaryContainer = AmberContainerLight,
    onPrimaryContainer = AmberOnContainerLight,
    secondary = JadeSecondaryLight,
    onSecondary = JadeOnSecondaryLight,
    secondaryContainer = JadeContainerLight,
    onSecondaryContainer = JadeOnContainerLight,
    tertiary = CoralTertiaryLight,
    onTertiary = CoralOnTertiaryLight,
    tertiaryContainer = CoralContainerLight,
    onTertiaryContainer = CoralOnContainerLight,
    background = WarmBackgroundLight,
    onBackground = WarmOnBackgroundLight,
    surface = WarmBackgroundLight,
    onSurface = WarmOnBackgroundLight,
    surfaceVariant = WarmSurfaceVariantLight,
    onSurfaceVariant = WarmOnSurfaceVariantLight,
    surfaceContainerLowest = WarmBackgroundLight,
    surfaceContainerLow = WarmSurfaceContainerLowLight,
    surfaceContainer = WarmSurfaceContainerLight,
    surfaceContainerHigh = WarmSurfaceContainerHighLight,
    surfaceContainerHighest = WarmSurfaceVariantLight,
    outline = WarmOutlineLight,
    outlineVariant = WarmOutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimaryDark,
    onPrimary = AmberOnPrimaryDark,
    primaryContainer = AmberContainerDark,
    onPrimaryContainer = AmberOnContainerDark,
    secondary = JadeSecondaryDark,
    onSecondary = JadeOnSecondaryDark,
    secondaryContainer = JadeContainerDark,
    onSecondaryContainer = JadeOnContainerDark,
    tertiary = CoralTertiaryDark,
    onTertiary = CoralOnTertiaryDark,
    tertiaryContainer = CoralContainerDark,
    onTertiaryContainer = CoralOnContainerDark,
    background = WarmBackgroundDark,
    onBackground = WarmOnBackgroundDark,
    surface = WarmBackgroundDark,
    onSurface = WarmOnBackgroundDark,
    surfaceVariant = WarmSurfaceVariantDark,
    onSurfaceVariant = WarmOnSurfaceVariantDark,
    surfaceContainerLowest = WarmBackgroundDark,
    surfaceContainerLow = WarmSurfaceContainerLowDark,
    surfaceContainer = WarmSurfaceContainerDark,
    surfaceContainerHigh = WarmSurfaceContainerHighDark,
    surfaceContainerHighest = WarmSurfaceVariantDark,
    outline = WarmOutlineDark,
    outlineVariant = WarmOutlineVariantDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

@Composable
fun JobJarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default now: Android 12+'s dynamic color pulls the whole palette from the phone's
    // wallpaper, silently discarding every color chosen above - that's what made the app look
    // grey regardless of what this file defined. This app has a real designed palette, so it
    // should actually be used; dynamicColor is left as a parameter (not deleted) in case that
    // tradeoff is ever wanted back for a specific build.
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
