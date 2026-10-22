package dev.foldman.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's text library: one set of colors and font choices shared by the local-files
 * library, the YouTube Music browser, and the podcast browser, so every surface the user
 * loads reads as the same instrument.
 *
 * Two surfaces, two inks:
 * - [Title]/[Sub]/[Header]: light ink for the dark smoked-glass panels (unfolded library).
 * - [Hand]/[Typed]: dark ink for the J-card paper (folded TAPES sheet).
 * [EngravedCaps] (in Chassis.kt) works on both.
 */
internal val Title = TextStyle(color = Color(0xFFF4EFE9), fontSize = 15.sp, fontWeight = FontWeight.Medium)
internal val Sub = TextStyle(color = Color(0xFFA79C92), fontSize = 12.sp)
internal val Header = TextStyle(color = Color(0xFFF4EFE9), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

internal val Hand = TextStyle(fontFamily = FontFamily.Cursive, color = DeckColors.Ink, fontSize = 20.sp)
internal val Typed = TextStyle(fontFamily = FontFamily.Monospace, color = DeckColors.Ink, fontSize = 12.sp)
