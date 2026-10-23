package dev.foldman.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.theme.ThemeMode

/**
 * Minimal settings, in the deck's material language: the theme override
 * (System / Light / Dark), persisted to the shared "foldman" prefs. Opened
 * from the gear on the deck.
 */
@Composable
fun SettingsSheet(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The sheet sits on the dark brown language in both themes; the cream
    // text stays legible on it either way.
    val cream = Color(0xFFE8DCC8)
    val muted = Color(0xFFA79C92)
    Column(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("SETTINGS", style = EngravedCaps.copy(fontSize = 11.sp, letterSpacing = 2.4.sp, color = cream))
        Spacer(Modifier.height(14.dp))
        Text(
            "Theme",
            style = TextStyle(
                fontFamily = FontFamily.Default, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = cream,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(4.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = mode == themeMode
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) DeckColors.Accent else Color.Transparent)
                        .clickable { onThemeChange(mode) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.label,
                        style = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else muted,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "System follows your phone's dark-theme setting.",
            style = TextStyle(fontFamily = FontFamily.Default, fontSize = 11.sp, color = muted),
        )
        Spacer(Modifier.height(8.dp))
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

/**
 * A small engraved gear that opens Settings. Drawn in the deck's key
 * language: a raised silver disc with a dark gear, sitting in an engraved
 * well. Fixed colors — it lives on the blue enamel in both themes.
 */
@Composable
fun SettingsGearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val action by rememberUpdatedState(onClick)
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val sounds = LocalMechSounds.current
    var down by remember { mutableStateOf(false) }
    val depth by animateFloatAsState(if (down) 1f else 0f, spring(stiffness = 2500f), label = "gear")
    Canvas(
        modifier
            .semantics { role = Role.Button; contentDescription = "Settings" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    sounds?.keyDown()
                    action()
                    tryAwaitRelease()
                    down = false
                })
            },
    ) {
        val r = size.minDimension / 2f
        val c = center
        val ink = Color(0xFF3A3C41)
        // Engraved well, then the raised disc.
        drawCircle(Color.Black.copy(alpha = 0.4f), r, c + Offset(0f, 1.5f))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFD8DBDF), Color(0xFF9A9DA2)), c, r),
            r * 0.94f, c - Offset(0f, depth * 2f),
        )
        // Gear: eight teeth, hub, and center hole.
        val gearR = r * 0.5f
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawRect(
                    ink,
                    topLeft = c + Offset(-gearR * 0.17f, -gearR * 1.3f),
                    size = Size(gearR * 0.34f, gearR * 0.36f),
                )
            }
        }
        drawCircle(ink, gearR, c)
        drawCircle(Color(0xFFD8DBDF), gearR * 0.55f, c)
        drawCircle(ink, gearR * 0.26f, c)
    }
}
