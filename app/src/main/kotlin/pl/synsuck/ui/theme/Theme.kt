package pl.synsuck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Brand = Color(0xFF1E5AA8)

@Composable
fun SynsuckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme(primary = Color(0xFF9FC3FF))
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Fixed palette for account color stripes; accounts store the index. */
object AccountColors {
    val palette = listOf(
        Color(0xFFE53935), // red
        Color(0xFFFB8C00), // orange
        Color(0xFF43A047), // green
        Color(0xFF1E88E5), // blue
        Color(0xFF8E24AA), // purple
        Color(0xFF00897B), // teal
    )

    fun of(index: Int): Color = palette[index.mod(palette.size)]
}
