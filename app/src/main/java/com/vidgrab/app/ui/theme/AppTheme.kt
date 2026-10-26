package com.vidgrab.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalContentColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppThemePalette(
    val id: String,
    val name: String,
    val modeLabel: String,
    val isDark: Boolean,
    val outerBg: Color,
    val phoneBg: Color,
    val cardBg: Color,
    val cardAltBg: Color,
    val sheetBg: Color,
    val pillBg: Color,
    val pillTrackBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val ctaBg: Color,
    val ctaText: Color,
    val swatchBg: Color,
    val swatchCard: Color,
    val dividerColor: Color,
    val successGreen: Color = Color(0xFF2C6954),
    val errorRed: Color = Color(0xFFE54D2E)
)

data class AccentSwatch(
    val id: String,
    val label: String,
    val color: Color,
    val text: Color
)

object AppThemes {
    val Obsidian = AppThemePalette(
        id = "obsidian",
        name = "Obsidian",
        modeLabel = "Dark",
        isDark = true,
        outerBg = Color(0xFF070709),
        phoneBg = Color(0xFF0E0E11),
        cardBg = Color(0xFF18181E),
        cardAltBg = Color(0xFF16161B),
        sheetBg = Color(0xFF141419),
        pillBg = Color(0xFF22222A),
        pillTrackBg = Color(0xFF101014),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFA1A1AA),
        textMuted = Color(0xFF71717A),
        ctaBg = Color(0xFFFFFFFF),
        ctaText = Color(0xFF0E0E11),
        swatchBg = Color(0xFF0E0E11),
        swatchCard = Color(0xFF24242E),
        dividerColor = Color(0xFF27272F),
        successGreen = Color(0xFF5FD3A6),
        errorRed = Color(0xFFFF7A66)
    )

    val Daylight = AppThemePalette(
        id = "daylight",
        name = "Daylight",
        modeLabel = "Light",
        isDark = false,
        outerBg = Color(0xFFDDE2EB),
        phoneBg = Color(0xFFF4F6F9),
        cardBg = Color(0xFFFFFFFF),
        cardAltBg = Color(0xFFFFFFFF),
        sheetBg = Color(0xFFFFFFFF),
        pillBg = Color(0xFFEAEEF4),
        pillTrackBg = Color(0xFFE3E7EF),
        textPrimary = Color(0xFF111216),
        textSecondary = Color(0xFF636875),
        textMuted = Color(0xFF8F95A3),
        ctaBg = Color(0xFF111216),
        ctaText = Color(0xFFFFFFFF),
        swatchBg = Color(0xFFF4F6F9),
        swatchCard = Color(0xFFFFFFFF),
        dividerColor = Color(0xFFE6EBF2),
        successGreen = Color(0xFF1C7A5A),
        errorRed = Color(0xFFB3261E)
    )

    val Botanical = AppThemePalette(
        id = "botanical",
        name = "Botanical",
        modeLabel = "Forest",
        isDark = true,
        outerBg = Color(0xFF060A08),
        phoneBg = Color(0xFF0C1310),
        cardBg = Color(0xFF14201B),
        cardAltBg = Color(0xFF121C17),
        sheetBg = Color(0xFF111B16),
        pillBg = Color(0xFF1E2E27),
        pillTrackBg = Color(0xFF080D0B),
        textPrimary = Color(0xFFF2F9F5),
        textSecondary = Color(0xFF8FA89B),
        textMuted = Color(0xFF60786B),
        ctaBg = Color(0xFFC8F169),
        ctaText = Color(0xFF141C04),
        swatchBg = Color(0xFF0C1310),
        swatchCard = Color(0xFF20332A),
        dividerColor = Color(0xFF1B2A23),
        successGreen = Color(0xFF9BE8B8),
        errorRed = Color(0xFFFF8A80)
    )

    val WarmSand = AppThemePalette(
        id = "sand",
        name = "Warm Sand",
        modeLabel = "Cream",
        isDark = false,
        outerBg = Color(0xFFE5DFD7),
        phoneBg = Color(0xFFF5F2EC),
        cardBg = Color(0xFFFFFFFF),
        cardAltBg = Color(0xFFFFFFFF),
        sheetBg = Color(0xFFFAF8F5),
        pillBg = Color(0xFFEAE4DC),
        pillTrackBg = Color(0xFFE2DBD1),
        textPrimary = Color(0xFF1C1917),
        textSecondary = Color(0xFF78716C),
        textMuted = Color(0xFFA8A29E),
        ctaBg = Color(0xFF1C1917),
        ctaText = Color(0xFFFFFFFF),
        swatchBg = Color(0xFFF5F2EC),
        swatchCard = Color(0xFFFFFFFF),
        dividerColor = Color(0xFFEDE6DD),
        successGreen = Color(0xFF1C7A5A),
        errorRed = Color(0xFFB3261E)
    )

    val ALL = listOf(Obsidian, Daylight, Botanical, WarmSand)

    fun fromId(id: String): AppThemePalette {
        return ALL.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Obsidian
    }
}

object AccentSwatches {
    val Sky = AccentSwatch("sky", "Sky", Color(0xFF9BE4F8), Color(0xFF0B1920))
    val Lime = AccentSwatch("lime", "Lime", Color(0xFFC8F169), Color(0xFF141C04))
    val Mint = AccentSwatch("mint", "Mint", Color(0xFF80F5C9), Color(0xFF072117))
    val Coral = AccentSwatch("coral", "Coral", Color(0xFFF26849), Color(0xFFFFFFFF))
    val Rose = AccentSwatch("rose", "Rose", Color(0xFFF6A6C1), Color(0xFF260B15))
    val Gold = AccentSwatch("gold", "Gold", Color(0xFFE5A93C), Color(0xFF1F1410))

    val ALL = listOf(Sky, Lime, Mint, Coral, Rose, Gold)

    fun fromId(id: String): AccentSwatch {
        return ALL.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Sky
    }
}

class ThemeManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("vidgrab_theme_prefs", Context.MODE_PRIVATE)

    private val _themeState = MutableStateFlow(AppThemes.fromId(prefs.getString(KEY_THEME, AppThemes.Obsidian.id) ?: AppThemes.Obsidian.id))
    val themeState: StateFlow<AppThemePalette> = _themeState.asStateFlow()

    private val _accentState = MutableStateFlow(AccentSwatches.fromId(prefs.getString(KEY_ACCENT, AccentSwatches.Sky.id) ?: AccentSwatches.Sky.id))
    val accentState: StateFlow<AccentSwatch> = _accentState.asStateFlow()

    fun setTheme(themeId: String) {
        val palette = AppThemes.fromId(themeId)
        _themeState.value = palette
        prefs.edit().putString(KEY_THEME, palette.id).apply()
    }

    fun setAccent(accentId: String) {
        val swatch = AccentSwatches.fromId(accentId)
        _accentState.value = swatch
        prefs.edit().putString(KEY_ACCENT, swatch.id).apply()
    }

    companion object {
        private const val KEY_THEME = "selected_theme_id"
        private const val KEY_ACCENT = "selected_accent_id"

        @Volatile
        private var instance: ThemeManager? = null

        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context).also { instance = it }
            }
        }
    }
}

val LocalAppPalette = staticCompositionLocalOf { AppThemes.Obsidian }
val LocalAccentSwatch = staticCompositionLocalOf { AccentSwatches.Sky }

object AppTheme {
    val colors: AppThemePalette
        @Composable
        get() = LocalAppPalette.current

    val accent: AccentSwatch
        @Composable
        get() = LocalAccentSwatch.current
}

@Composable
fun VidGrabTheme(
    context: Context,
    content: @Composable () -> Unit
) {
    val themeManager = ThemeManager.getInstance(context)
    val currentTheme by themeManager.themeState.collectAsState()
    val currentAccent by themeManager.accentState.collectAsState()

    // Keep Material3 defaults in sync with the selected palette. Without this, any component
    // that relies on LocalContentColor would render black-on-black in the dark themes.
    val scheme = if (currentTheme.isDark) {
        darkColorScheme(
            primary = currentAccent.color,
            onPrimary = currentAccent.text,
            primaryContainer = currentAccent.color,
            onPrimaryContainer = currentAccent.text,
            secondary = currentAccent.color,
            onSecondary = currentAccent.text,
            background = currentTheme.phoneBg,
            onBackground = currentTheme.textPrimary,
            surface = currentTheme.cardBg,
            onSurface = currentTheme.textPrimary,
            surfaceVariant = currentTheme.pillBg,
            onSurfaceVariant = currentTheme.textSecondary,
            outline = currentTheme.dividerColor,
            error = currentTheme.errorRed,
            onError = currentTheme.ctaText
        )
    } else {
        lightColorScheme(
            primary = currentAccent.color,
            onPrimary = currentAccent.text,
            primaryContainer = currentAccent.color,
            onPrimaryContainer = currentAccent.text,
            secondary = currentAccent.color,
            onSecondary = currentAccent.text,
            background = currentTheme.phoneBg,
            onBackground = currentTheme.textPrimary,
            surface = currentTheme.cardBg,
            onSurface = currentTheme.textPrimary,
            surfaceVariant = currentTheme.pillBg,
            onSurfaceVariant = currentTheme.textSecondary,
            outline = currentTheme.dividerColor,
            error = currentTheme.errorRed,
            onError = currentTheme.ctaText
        )
    }

    CompositionLocalProvider(
        LocalAppPalette provides currentTheme,
        LocalAccentSwatch provides currentAccent,
        LocalContentColor provides currentTheme.textPrimary,
        LocalTextStyle provides TextStyle(color = currentTheme.textPrimary)
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
