package com.geovisual.kenburns.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Teal = Color(0xFF4FD1C5)
val Purple = Color(0xFF503AA8)
val Orange = Color(0xFFFFA64D)
val Green = Color(0xFF7BD88F)
val Pink = Color(0xFFFF7EB6)

/** Accent colors cycled per item so no single color carries everything. */
val Accents = listOf(Teal, Orange, Green, Pink)

val Background = Color(0xFF121216)
val Surface = Color(0xFF1C1C22)

private val Scheme = darkColorScheme(
    primary = Purple,          // buttons and interactive fills only
    onPrimary = Color.White,
    secondary = Teal,
    background = Background,
    surface = Surface,
    onBackground = Color(0xFFE6E6EA),
    onSurface = Color(0xFFE6E6EA),
)

@Composable
fun KenBurnsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
