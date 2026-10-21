package com.mattdixon.jobjar.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = TealOnPrimaryLight,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = SlateOnContainerLight,
    tertiary = HoneyTertiaryLight,
    onTertiary = HoneyOnTertiaryLight,
    tertiaryContainer = HoneyContainerLight,
    onTertiaryContainer = HoneyOnContainerLight,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnBackgroundLight,
    surface = NeutralBackgroundLight,
    onSurface = NeutralOnBackgroundLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    surfaceContainerLowest = NeutralSurfaceContainerLowestLight,
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
    primary = TealPrimaryDark,
    onPrimary = TealOnPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealOnContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = SlateOnContainerDark,
    tertiary = HoneyTertiaryDark,
    onTertiary = HoneyOnTertiaryDark,
    tertiaryContainer = HoneyContainerDark,
    onTertiaryContainer = HoneyOnContainerDark,
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

/**
 * The few app-specific colors Material's scheme has no role for. Material's filled Card
 * defaults to surfaceContainerHighest - the darkest grey in the light scheme - which is what
 * made every list read as a stack of muddy grey slabs. Cards here are instead a clean surface
 * (white in light mode) with a hairline border; use [appCardColors]/[appCardBorder].
 */
@Immutable
data class AppColors(val card: Color, val cardBorder: Color)

private val LightAppColors = AppColors(card = CardLight, cardBorder = CardBorderLight)
private val DarkAppColors = AppColors(card = CardDark, cardBorder = CardBorderDark)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

@Composable
@ReadOnlyComposable
fun appCardBorder(): BorderStroke = BorderStroke(1.dp, LocalAppColors.current.cardBorder)

@Composable
fun appCardColors(): CardColors = CardDefaults.cardColors(containerColor = LocalAppColors.current.card)

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

    CompositionLocalProvider(LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
