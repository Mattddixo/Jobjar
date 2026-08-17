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
    primary = TealPrimaryLight,
    onPrimary = TealOnPrimaryLight,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = SandSecondaryLight,
    onSecondary = SandOnSecondaryLight,
    secondaryContainer = SandContainerLight,
    onSecondaryContainer = SandOnContainerLight,
    tertiary = HoneyTertiaryLight,
    onTertiary = HoneyOnTertiaryLight,
    tertiaryContainer = HoneyContainerLight,
    onTertiaryContainer = HoneyOnContainerLight,
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
    surfaceContainerHighest = WarmSurfaceContainerHighestLight,
    outline = WarmOutlineLight,
    outlineVariant = WarmOutlineVariantLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = TealOnPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealOnContainerDark,
    secondary = SandSecondaryDark,
    onSecondary = SandOnSecondaryDark,
    secondaryContainer = SandContainerDark,
    onSecondaryContainer = SandOnContainerDark,
    tertiary = HoneyTertiaryDark,
    onTertiary = HoneyOnTertiaryDark,
    tertiaryContainer = HoneyContainerDark,
    onTertiaryContainer = HoneyOnContainerDark,
    background = WarmBackgroundDark,
    onBackground = WarmOnBackgroundDark,
    surface = WarmBackgroundDark,
    onSurface = WarmOnBackgroundDark,
    surfaceVariant = WarmSurfaceVariantDark,
    onSurfaceVariant = WarmOnSurfaceVariantDark,
    surfaceContainerLowest = WarmSurfaceContainerLowestDark,
    surfaceContainerLow = WarmSurfaceContainerLowDark,
    surfaceContainer = WarmSurfaceContainerDark,
    surfaceContainerHigh = WarmSurfaceContainerHighDark,
    surfaceContainerHighest = WarmSurfaceContainerHighestDark,
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
