package com.nazhi.app.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class NazhiColorTokens(
    val background: Color = Color(0xFFFFF8EA),
    val backgroundMuted: Color = Color(0xFFF5EBD7),
    val surface: Color = Color(0xFFFFFCF4),
    val surfaceRaised: Color = Color(0xFFFFFFFF),
    val panel: Color = Color(0xFFF1F7EC),
    val panelBorder: Color = Color(0xFFD6E2CE),
    val grass: Color = Color(0xFF2F7D57),
    val grassDark: Color = Color(0xFF1E573D),
    val grassSoft: Color = Color(0xFFDCEED6),
    val soil: Color = Color(0xFF8A6243),
    val soilSoft: Color = Color(0xFFE9D2AE),
    val wheat: Color = Color(0xFFE5B758),
    val wheatSoft: Color = Color(0xFFFFEAB7),
    val sky: Color = Color(0xFF4D93AA),
    val skySoft: Color = Color(0xFFD9EEF3),
    val fruit: Color = Color(0xFFD9664D),
    val sunlight: Color = Color(0xFFE68A35),
    val issue: Color = Color(0xFFB65A37),
    val issueSoft: Color = Color(0xFFF7D6C7),
    val textPrimary: Color = Color(0xFF24352C),
    val textSecondary: Color = Color(0xFF667566),
    val navigationBar: Color = Color(0xFFFFFCF4)
)

object NazhiSpacing {
    val screen = 16.dp
    val card = 16.dp
    val compact = 8.dp
    val tiny = 4.dp
}

object NazhiCorners {
    val card = 8.dp
    val chip = 8.dp
    val panel = 8.dp
}

object NazhiTokens {
    val colors: NazhiColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalNazhiColors.current

    val spacing = NazhiSpacing
    val corners = NazhiCorners
}

enum class NazhiStatusKind {
    CAPTURED,
    PENDING,
    DRAFT,
    SETTLED,
    ISSUE
}

data class NazhiStatusPalette(
    val container: Color,
    val content: Color,
    val accent: Color,
    val border: Color
)

@Composable
@ReadOnlyComposable
fun NazhiStatusKind.palette(): NazhiStatusPalette {
    val colors = NazhiTokens.colors
    return when (this) {
        NazhiStatusKind.CAPTURED -> NazhiStatusPalette(
            container = colors.skySoft,
            content = colors.textPrimary,
            accent = colors.sky,
            border = colors.sky.copy(alpha = 0.36f)
        )
        NazhiStatusKind.PENDING -> NazhiStatusPalette(
            container = colors.grassSoft,
            content = colors.textPrimary,
            accent = colors.grass,
            border = colors.grass.copy(alpha = 0.34f)
        )
        NazhiStatusKind.DRAFT -> NazhiStatusPalette(
            container = colors.wheatSoft,
            content = colors.textPrimary,
            accent = colors.wheat,
            border = colors.wheat.copy(alpha = 0.52f)
        )
        NazhiStatusKind.SETTLED -> NazhiStatusPalette(
            container = Color(0xFFD7E9CE),
            content = colors.textPrimary,
            accent = colors.grassDark,
            border = colors.grassDark.copy(alpha = 0.34f)
        )
        NazhiStatusKind.ISSUE -> NazhiStatusPalette(
            container = colors.issueSoft,
            content = colors.textPrimary,
            accent = colors.issue,
            border = colors.issue.copy(alpha = 0.42f)
        )
    }
}

@Composable
fun NazhiTheme(content: @Composable () -> Unit) {
    val nazhiColors = NazhiColorTokens()
    val colorScheme = lightColorScheme(
        primary = nazhiColors.grass,
        onPrimary = Color.White,
        primaryContainer = nazhiColors.grassSoft,
        onPrimaryContainer = nazhiColors.grassDark,
        secondary = nazhiColors.soil,
        onSecondary = Color.White,
        secondaryContainer = nazhiColors.soilSoft,
        onSecondaryContainer = Color(0xFF3D2D21),
        tertiary = nazhiColors.sky,
        onTertiary = Color.White,
        tertiaryContainer = nazhiColors.skySoft,
        onTertiaryContainer = Color(0xFF143E4B),
        error = nazhiColors.issue,
        onError = Color.White,
        errorContainer = nazhiColors.issueSoft,
        onErrorContainer = Color(0xFF5F2618),
        background = nazhiColors.background,
        onBackground = nazhiColors.textPrimary,
        surface = nazhiColors.surface,
        onSurface = nazhiColors.textPrimary,
        surfaceVariant = nazhiColors.panel,
        onSurfaceVariant = nazhiColors.textSecondary,
        outline = Color(0xFF8B9B8B),
        outlineVariant = nazhiColors.panelBorder
    )

    CompositionLocalProvider(LocalNazhiColors provides nazhiColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

private val LocalNazhiColors = staticCompositionLocalOf { NazhiColorTokens() }
