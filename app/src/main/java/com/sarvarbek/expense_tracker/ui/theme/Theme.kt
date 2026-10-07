package com.sarvarbek.expense_tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sarvarbek.expense_tracker.R

/** Semantic color tokens. Read via [AppTheme.colors]; never hardcode. */
@Immutable
data class AppColors(
    val accent: Color,
    val onAccent: Color,
    val hero: Color,
    val onHero: Color,
    val bg: Color,
    val card: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val danger: Color,
    val navInactive: Color,
) {
    companion object {
        val Light = AppColors(
            accent = Color(0xFF1C7A5E),
            onAccent = Color(0xFFFFFFFF),
            hero = Color(0xFF123328),
            onHero = Color(0xFFFFFFFF),
            bg = Color(0xFFF5F3EE),
            card = Color(0xFFFFFFFF),
            border = Color(0xFFEAE7E0),
            text = Color(0xFF1B1A17),
            muted = Color(0xFF6F6B64), // AA on bg
            danger = Color(0xFFC6443A),
            navInactive = Color(0xFF6F6B64),
        )
        val Dark = AppColors(
            accent = Color(0xFF3FB58C),
            onAccent = Color(0xFF0B1F17),
            hero = Color(0xFF0E2A20),
            onHero = Color(0xFFECEAE5),
            bg = Color(0xFF121412),
            card = Color(0xFF1C1F1D),
            border = Color(0xFF2A2E2B),
            text = Color(0xFFECEAE5),
            muted = Color(0xFF9A968E),
            danger = Color(0xFFE57368),
            navInactive = Color(0xFF9A968E),
        )
    }
}

/** Corner radii. Bigger surface, rounder corner. */
object AppRadii {
    val sm = 12.dp
    val md = 16.dp
    val card = 24.dp
    val hero = 32.dp
    val chip = 999.dp
}

/** Page gutter and vertical rhythm (8pt grid). */
object AppSpace {
    val page = 20.dp
    val gap = 16.dp
    val section = 28.dp
}

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

private const val TABULAR = "tnum"

private fun style(size: Int, weight: FontWeight, height: Float? = null, spacing: Float = 0f, tabular: Boolean = false) =
    TextStyle(
        fontFamily = Manrope,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = height?.let { (size * it).sp } ?: TextStyle.Default.lineHeight,
        letterSpacing = spacing.sp,
        fontFeatureSettings = if (tabular) TABULAR else null,
    )

/**
 * Type scale (Manrope). Amounts use tabular figures so digits line up.
 * No colors here: Text takes LocalContentColor from its container (Card, Snackbar, Badge, chip).
 * Secondary text passes color = AppTheme.colors.muted at the call site.
 */
private val typography = Typography(
    displayLarge = style(44, FontWeight.ExtraBold, 1.05f, -1.6f, tabular = true),
    displayMedium = style(45, FontWeight.Normal, 1.15f),
    displaySmall = style(30, FontWeight.ExtraBold, 1.1f, -1f, tabular = true),
    headlineMedium = style(28, FontWeight.ExtraBold, 1.15f, -0.8f),
    headlineSmall = style(24, FontWeight.Normal, 1.33f),
    titleLarge = style(20, FontWeight.Bold, 1.25f, -0.3f),
    titleMedium = style(16, FontWeight.Bold, 1.3f),
    titleSmall = style(15, FontWeight.Bold, 1.3f, tabular = true),
    bodyLarge = style(16, FontWeight.Medium, 1.45f),
    bodyMedium = style(14, FontWeight.Medium, 1.45f),
    bodySmall = style(13, FontWeight.Medium, 1.35f),
    labelLarge = style(15, FontWeight.Bold),
    labelMedium = style(13, FontWeight.SemiBold),
    labelSmall = style(12, FontWeight.SemiBold),
)

private val LocalAppColors = staticCompositionLocalOf { AppColors.Light }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) AppColors.Dark else AppColors.Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accent.copy(alpha = 0.14f),
        onPrimaryContainer = c.text,
        secondaryContainer = c.accent.copy(alpha = 0.14f),
        onSecondaryContainer = c.text,
        background = c.bg,
        onBackground = c.text,
        surface = c.card,
        onSurface = c.text,
        surfaceContainer = c.card,
        surfaceContainerLow = c.card,
        surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.card,
        surfaceContainerLowest = c.card,
        onSurfaceVariant = c.muted,
        outline = c.border,
        outlineVariant = c.border,
        error = c.danger,
        inverseSurface = c.hero,
        inverseOnSurface = c.onHero,
        inversePrimary = c.onHero,
    )
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(
                small = RoundedCornerShape(AppRadii.sm),
                medium = RoundedCornerShape(AppRadii.md),
                large = RoundedCornerShape(AppRadii.card),
                extraLarge = RoundedCornerShape(AppRadii.card),
            ),
            content = content,
        )
    }
}
