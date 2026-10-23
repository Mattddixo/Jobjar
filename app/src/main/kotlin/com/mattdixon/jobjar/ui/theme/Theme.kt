package com.mattdixon.jobjar.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
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
    secondary = SeaSecondaryLight,
    onSecondary = SeaOnSecondaryLight,
    secondaryContainer = SeaContainerLight,
    onSecondaryContainer = SeaOnContainerLight,
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
    secondary = SeaSecondaryDark,
    onSecondary = SeaOnSecondaryDark,
    secondaryContainer = SeaContainerDark,
    onSecondaryContainer = SeaOnContainerDark,
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
 * Colours Material doesn't have a slot for: white bordered cards (Material's filled cards are a
 * heavy grey), the header bars, and the jar picture. Use [appCardColors], [appCardBorder] and
 * [appTopBarColors].
 */
@Immutable
data class AppColors(
    val card: Color,
    val cardBorder: Color,
    val header: Color,
    val onHeader: Color,
    val onHeaderMuted: Color,
    val jarGlass: Color,
    val jarGlassEdge: Color
)

private val LightAppColors = AppColors(
    CardLight, CardBorderLight, HeaderLight, OnHeaderLight, OnHeaderMutedLight, JarGlassLight, JarGlassEdgeLight
)
private val DarkAppColors = AppColors(
    CardDark, CardBorderDark, HeaderDark, OnHeaderDark, OnHeaderMutedDark, JarGlassDark, JarGlassEdgeDark
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

@Composable
@ReadOnlyComposable
fun appCardBorder(): BorderStroke = BorderStroke(1.dp, LocalAppColors.current.cardBorder)

@Composable
fun appCardColors(): CardColors = CardDefaults.cardColors(containerColor = LocalAppColors.current.card)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopBarColors(): TopAppBarColors {
    val colors = LocalAppColors.current
    return TopAppBarDefaults.topAppBarColors(
        containerColor = colors.header,
        scrolledContainerColor = colors.header,
        titleContentColor = colors.onHeader,
        navigationIconContentColor = colors.onHeader,
        actionIconContentColor = colors.onHeader
    )
}

@Composable
fun JobJarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off: dynamic colour would replace the palette with one taken from the wallpaper.
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
