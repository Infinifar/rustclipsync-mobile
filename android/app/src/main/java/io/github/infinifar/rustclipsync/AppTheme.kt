package io.github.infinifar.rustclipsync

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

internal enum class ThemeMode(val title: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark) }

@Composable
internal fun AppTheme(content: @Composable (ThemeMode, (ThemeMode) -> Unit) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var mode by remember {
        mutableStateOf(ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM)
    }
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val background = if (dark) Color.Black else Color.White
    val colors = (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
        .copy(background = background, surface = background)
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb()))
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        content(mode) { value -> mode = value; preferences.edit().putString("theme", value.name).apply() }
    }
}
