package io.github.atrejoe.travelguide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1F4E5F)
private val Sand = Color(0xFFF2E8CF)
private val Terracotta = Color(0xFFE07A5F)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Terracotta,
    background = Color(0xFFFBF8F1),
    surface = Color(0xFFFBF8F1),
    surfaceVariant = Sand,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8CC7D9),
    onPrimary = Color(0xFF00333F),
    secondary = Terracotta,
    background = Color(0xFF111A1D),
    surface = Color(0xFF111A1D),
    surfaceVariant = Color(0xFF223238),
)

@Composable
fun TravelGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
