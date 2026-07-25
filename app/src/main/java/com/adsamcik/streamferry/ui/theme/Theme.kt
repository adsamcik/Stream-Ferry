package com.adsamcik.streamferry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * The static schemes are deliberately complete. This keeps every Material role in the same teal,
 * blue-grey and warm-gold tonal families when dynamic colour is disabled, including the newer
 * surface-container and fixed-accent roles used by Material 3 components.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF0FF),
    onPrimaryContainer = Color(0xFF001F24),
    inversePrimary = Color(0xFF4FD8E8),
    secondary = Color(0xFF4A6268),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7ED),
    onSecondaryContainer = Color(0xFF051F23),
    tertiary = Color(0xFF745B00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE087),
    onTertiaryContainer = Color(0xFF241A00),
    background = Color(0xFFF5FAFB),
    onBackground = Color(0xFF171D1F),
    surface = Color(0xFFF5FAFB),
    onSurface = Color(0xFF171D1F),
    surfaceVariant = Color(0xFFDBE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
    surfaceTint = Color(0xFF006874),
    inverseSurface = Color(0xFF2C3133),
    inverseOnSurface = Color(0xFFECF2F3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBFC8CA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF5FAFB),
    surfaceContainer = Color(0xFFE9EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDDE3E4),
    surfaceContainerLow = Color(0xFFEFF4F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD5DBDC),
    primaryFixed = Color(0xFF9EF0FF),
    primaryFixedDim = Color(0xFF4FD8E8),
    onPrimaryFixed = Color(0xFF001F24),
    onPrimaryFixedVariant = Color(0xFF004F58),
    secondaryFixed = Color(0xFFCDE7ED),
    secondaryFixedDim = Color(0xFFB1CBD1),
    onSecondaryFixed = Color(0xFF051F23),
    onSecondaryFixedVariant = Color(0xFF334A4F),
    tertiaryFixed = Color(0xFFFFE087),
    tertiaryFixedDim = Color(0xFFE8C34C),
    onTertiaryFixed = Color(0xFF241A00),
    onTertiaryFixedVariant = Color(0xFF584500),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8E8),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9EF0FF),
    inversePrimary = Color(0xFF006874),
    secondary = Color(0xFFB1CBD1),
    onSecondary = Color(0xFF1C3439),
    secondaryContainer = Color(0xFF334A4F),
    onSecondaryContainer = Color(0xFFCDE7ED),
    tertiary = Color(0xFFE8C34C),
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF584500),
    onTertiaryContainer = Color(0xFFFFE087),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDDE3E4),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDDE3E4),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
    surfaceTint = Color(0xFF4FD8E8),
    inverseSurface = Color(0xFFDDE3E4),
    inverseOnSurface = Color(0xFF2C3133),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343A3B),
    surfaceContainer = Color(0xFF1B2123),
    surfaceContainerHigh = Color(0xFF262B2D),
    surfaceContainerHighest = Color(0xFF313638),
    surfaceContainerLow = Color(0xFF171D1F),
    surfaceContainerLowest = Color(0xFF090F10),
    surfaceDim = Color(0xFF0E1415),
    primaryFixed = Color(0xFF9EF0FF),
    primaryFixedDim = Color(0xFF4FD8E8),
    onPrimaryFixed = Color(0xFF001F24),
    onPrimaryFixedVariant = Color(0xFF004F58),
    secondaryFixed = Color(0xFFCDE7ED),
    secondaryFixedDim = Color(0xFFB1CBD1),
    onSecondaryFixed = Color(0xFF051F23),
    onSecondaryFixedVariant = Color(0xFF334A4F),
    tertiaryFixed = Color(0xFFFFE087),
    tertiaryFixedDim = Color(0xFFE8C34C),
    onTertiaryFixed = Color(0xFF241A00),
    onTertiaryFixedVariant = Color(0xFF584500),
)

private val StreamFerryShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val DefaultTypography = Typography()
private val StreamFerryTypography = Typography(
    displayLarge = DefaultTypography.displayLarge.copy(fontWeight = FontWeight.Black),
    displayMedium = DefaultTypography.displayMedium.copy(fontWeight = FontWeight.ExtraBold),
    displaySmall = DefaultTypography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = DefaultTypography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = DefaultTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = DefaultTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = DefaultTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = DefaultTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = DefaultTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = DefaultTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * Stream Ferry's stable Material 3 theme.
 *
 * Wallpaper-derived colour is used as one complete scheme when enabled; brand roles are not spliced
 * into it because doing so can break the tonal and contrast relationships Android generated. The
 * complete teal schemes above are the deterministic light and dark fallbacks.
 */
@Composable
fun StreamFerryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StreamFerryTypography,
        shapes = StreamFerryShapes,
        content = content,
    )
}
