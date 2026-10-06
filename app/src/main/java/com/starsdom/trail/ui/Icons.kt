package com.starsdom.trail.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A Material Symbols Outlined drawable (§7), copied from the official symbols/android/ under its own name:
 * _wght500 normally, _wght600fill1 for the 记录 keys and selected / 跟随, _fill1 in notifications.
 */
@Composable
fun Icon(@DrawableRes id: Int, description: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) =
  Image(painterResource(id), description, modifier.size(size), colorFilter = ColorFilter.tint(tint))
