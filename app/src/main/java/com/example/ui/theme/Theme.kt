package com.example.ui.theme

import android.os.Build
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFFF9B55),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF281306),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF55301B),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDBC4),
    secondary = androidx.compose.ui.graphics.Color(0xFFCEC4BC),
    tertiary = androidx.compose.ui.graphics.Color(0xFF88D5AA),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF073720),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF153E2A),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFC1F0D3),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF36312E),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFF4EDE8),
    background = androidx.compose.ui.graphics.Color(0xFF171615),
    surface = androidx.compose.ui.graphics.Color(0xFF222120),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF302D2A),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5F3F0),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5F3F0),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC8C1BA),
    outline = androidx.compose.ui.graphics.Color(0xFF918880),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF494541),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC04B00),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDDC5),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF381600),
    secondary = androidx.compose.ui.graphics.Color(0xFF655B53),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE9E5E0),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF25211E),
    background = androidx.compose.ui.graphics.Color(0xFFEDEEEE),
    surface = androidx.compose.ui.graphics.Color(0xFFFAFAF9),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4E6E5),
    onBackground = androidx.compose.ui.graphics.Color(0xFF171717),
    onSurface = androidx.compose.ui.graphics.Color(0xFF171717),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF595B5A),
    outline = androidx.compose.ui.graphics.Color(0xFF737572),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFD4D7D5),
    tertiary = androidx.compose.ui.graphics.Color(0xFF2B6A43),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFD6ECDC),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF102B1B),

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = androidx.compose.material3.Shapes(
      extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
      small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
      medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
      large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
      extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    ),
    content = content
  )
}
