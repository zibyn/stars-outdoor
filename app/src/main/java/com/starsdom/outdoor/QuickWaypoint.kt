package com.starsdom.outdoor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// 一键标注当前位置 (ux-v2 §9.1).

enum class WaypointStep { Save, Wait, Ask }

/** §9.1 质量门槛: a fix of [fixAccuracyM] (null: none in the last 30 s) after waiting [waitedMs]. */
fun waypointStep(fixAccuracyM: Double?, waitedMs: Long): WaypointStep = when {
  fixAccuracyM != null && fixAccuracyM <= 50 -> WaypointStep.Save
  waitedMs >= 60_000 -> WaypointStep.Ask
  else -> WaypointStep.Wait
}

/** " ±8 m", or nothing when the accuracy isn't known. */
fun accuracyText(m: Double?) = m?.let { " ±${it.roundToInt()} m" }.orEmpty()

/** The 标注 icon, TalkBack「标注」; [waiting] for a fix, a [Spinner] round it (#122: no ±m, no words). */
@Composable
fun MarkIcon(waiting: Boolean, size: Dp = 24.dp) = Box(Modifier.semantics { if (waiting) stateDescription = "定位中，点一下取消" }, contentAlignment = Alignment.Center) {
  Icon(R.drawable.add_location_alt_wght500_24px, "标注", size = size)
  if (waiting) Spinner(Modifier.size(size + 16.dp), 3.dp)
}

/** 在地图上选: the cross over the map's centre, where the 标注 goes. */
@Composable
fun Crosshair(modifier: Modifier = Modifier) {
  val colors = listOf(5f to semantic.stroke, 2.5f to MaterialTheme.colorScheme.onSurface)
  Box(modifier.size(40.dp).drawBehind {
    val c = Offset(size.width / 2, size.height / 2)
    for ((w, color) in colors) {
      drawLine(color, Offset(0f, c.y), Offset(size.width, c.y), w)
      drawLine(color, Offset(c.x, 0f), Offset(c.x, size.height), w)
    }
  })
}
