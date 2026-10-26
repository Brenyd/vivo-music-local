package com.brenyd.vmusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colors measured from vivo Music (dark mode). */
object VColors {
    val Bg = Color(0xFF000000)
    val Field = Color(0xFF292929)      // search bar
    val Sheet = Color(0xFF282828)      // bottom sheets
    val Tile = Color(0xFF333333)       // grid tiles / buttons on sheets
    val Placeholder = Color(0xFF2A2A2A)
    val MiniBar = Color(0xFF0D0D0D)
    val Accent = Color(0xFFF05D56)     // vivo red (tabs, buttons, playing song)
    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0xFFA3A3A3)
    val Text3 = Color(0xFF6F6F6F)
    val Divider = Color(0xFF1E1E1E)
    val Blue = Color(0xFF6F9FEF)       // matched check / SQ badge
    val NewBadge = Color(0xFFE8A13A)
}

@Composable
fun VTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VColors.Accent,
            onPrimary = Color.White,
            secondary = VColors.Accent,
            background = VColors.Bg,
            onBackground = VColors.Text,
            surface = VColors.Bg,
            onSurface = VColors.Text,
            surfaceVariant = VColors.Sheet,
            onSurfaceVariant = VColors.Text2,
            surfaceContainer = VColors.Sheet,
            surfaceContainerHigh = VColors.Sheet,
            surfaceContainerLow = VColors.Sheet,
            outline = VColors.Text3,
        ),
        content = content,
    )
}
