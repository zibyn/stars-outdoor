package com.starsdom.outdoor

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// 记录中 (ux-v3 §8.3): the numbers (a plain line until the 数据窄条, #175) and the keys for gloves.

/**
 * 沿轨 in the 顶部数据 (§3.4) on a track [lengthM] long: [atM] the 沿轨里程 (empty off the track), [offM] the fix's
 * distance to it (null: no fix), [accuracyM] how good that fix says it is, [alert] while the 偏离提醒 is on.
 */
data class AlongNow(
  val atM: List<Double>,
  val lengthM: Double,
  val offM: Double? = null,
  val accuracyM: Double? = null,
  val alert: Boolean = false,
)

/**
 * A 顶部数据 page: value to label cells, on the first under the whole-width 沿轨 [row], in the warning colour while
 * [alert] (偏离), greyed with a [note] when the fix is poor.
 */
data class ActivePage(val cells: List<Pair<String, String>>, val row: String? = null, val alert: Boolean = false, val grey: Boolean = false, val note: String? = null)

/**
 * The 顶部数据 pages (§3.4): [stats] of the recording so far (null before its first point), [altitudeM] of the last
 * fix, [along] with a 参考轨迹. With one, 沿轨 tops three cells and 剩余 / 海拔 take 均速 / 最高海拔's place.
 */
fun activePages(stats: TrackStats?, altitudeM: Double?, battery: Int?, along: AlongNow? = null): List<ActivePage> {
  val km = (stats?.distanceM ?: 0.0) / 1000
  val ms = stats?.durationMs ?: 0L
  val hours = ms / 3_600_000.0
  // Under 100 m, a pace or speed would be noise.
  val moved = km >= 0.1 && ms > 0
  val pace = if (moved) (ms / km / 1000).roundToInt() else 0
  val walked = listOf(
    String.format(Locale.ROOT, "%.2f", km) to "已走 km",
    String.format(Locale.ROOT, "%d:%02d", ms / 3_600_000, ms / 60_000 % 60) to "用时",
    "${(stats?.ascentM ?: 0.0).roundToInt()}" to "爬升 m",
  )
  val paceCell = (if (moved) String.format(Locale.ROOT, "%d:%02d", pace / 60, pace % 60) else "—") to "km 用时"
  val batteryCell = (battery?.let { "$it%" } ?: "—") to "电量"
  val altitudeCell = (altitudeM?.roundToInt()?.toString() ?: "—") to "海拔 m"
  if (along == null) return listOf(
    ActivePage(walked + altitudeCell),
    ActivePage(listOf(
      paceCell,
      (if (moved) String.format(Locale.ROOT, "%.1f", km / hours) else "—") to "均速 km/h",
      (stats?.profile?.maxOfOrNull { it.second }?.roundToInt()?.toString() ?: "—") to "最高海拔 m",
      batteryCell,
    )),
  )
  val row = when {
    along.alert -> "偏离" + along.offM?.let { " ${it.roundToInt()} m" }.orEmpty()
    along.offM == null -> "沿轨 —"
    along.atM.isEmpty() -> "不在轨迹上"
    else -> "沿轨 " + kmsText(along.atM)
  }
  // With a fix, as the 参考轨迹条 (§3.7).
  val poor = along.offM != null && poorFix(along.accuracyM)
  // 剩余 counts from one place on the track only: several, or 偏离, and it's unknown.
  val at = along.atM.singleOrNull()?.takeIf { !along.alert }
  return listOf(
    ActivePage(walked, row, along.alert, poor, ("精度差" + accuracyText(along.accuracyM)).takeIf { poor }),
    ActivePage(listOf(
      paceCell,
      (at?.let { kmText(along.lengthM - it) } ?: "—") to "剩余 km",
      altitudeCell,
      batteryCell,
    )),
  )
}

/** One short buzz (ux-v3 §6 震动): 开始, 暂停, 继续, 按住计时完成, 标注. */
fun Context.buzz() {
  @Suppress("DEPRECATION") // VibratorManager needs API 31; this works on all.
  getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
}

/**
 * Fires [onDone] once held [holdMs], filling linearly meanwhile; let go early and [onTooShort]. Timed by frames,
 * not an animation, so 移除动画 doesn't shorten it (§3.5). TalkBack can't hold: [action] is its double-tap (§4.5).
 */
@Composable
internal fun HoldKey(@DrawableRes icon: Int, text: String, action: String, holdMs: Int, color: Color, modifier: Modifier, onTooShort: () -> Unit, onDone: () -> Unit) {
  var progress by remember { mutableFloatStateOf(0f) }
  val context = LocalContext.current
  val done by rememberUpdatedState(onDone)
  val tooShort by rememberUpdatedState(onTooShort)
  val on = contentColorFor(color)
  Box(
    modifier.heightIn(min = 56.dp).background(color, CircleShape).clip(CircleShape)
      .clearAndSetSemantics { contentDescription = text; onClick(action) { done(); true } }
      .pointerInput(holdMs) {
        detectTapGestures(onPress = {
          coroutineScope {
            val timer = launch {
              val start = withFrameMillis { it }
              while (progress < 1f) progress = ((withFrameMillis { it } - start) / holdMs.toFloat()).coerceAtMost(1f)
              context.buzz()
              done()
            }
            tryAwaitRelease()
            if (progress < 1f) tooShort()
            timer.cancel()
            progress = 0f
          }
        })
      },
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.matchParentSize().wrapContentWidth(Alignment.Start).fillMaxWidth(progress).background(on.copy(alpha = 0.35f)))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, null, tint = on)
      Text(text, Modifier.padding(start = Space.XS), on, style = MaterialTheme.typography.labelLarge)
    }
  }
}

/** A 小抽屉 of plain rows (§4.1), each ≥ 56 dp; a row with a Boolean is a switch. */
@Composable
fun SmallSheet(rows: List<Triple<String, Boolean?, () -> Unit>>, modifier: Modifier = Modifier) {
  Sheet(modifier) {
    for ((label, on, onClick) in rows) {
      if (on != null) Switch(label, on, onClick)
      else Text(label, Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).wrapContentHeight())
    }
  }
}

/**
 * A 半屏抽屉 (ux-v2 §4.1) over the lower half of the map, no scrim: the map above still takes gestures. Its handle drags
 * up to [full] screen, down to half and then away ([onClose]); a tap toggles.
 */
@Composable
fun HalfDrawer(full: Boolean, onFull: (Boolean) -> Unit, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  var drag by remember { mutableFloatStateOf(0f) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // A Surface, so the whole drawer takes every touch inside it (#138).
    DrawerSurface(
      // Full, the 提示条 goes over its foot instead of off the top.
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (full) maxHeight else maxHeight / 2).then(if (full) Modifier else Modifier.hintAnchor()),
    ) { Column(Modifier.then(if (full) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()) {
      Box(
        Modifier.fillMaxWidth().draggable(
          rememberDraggableState { drag += it }, Orientation.Vertical,
          onDragStarted = { drag = 0f },
          onDragStopped = { if (drag < -60) onFull(true) else if (drag > 60) { if (full) onFull(false) else onClose() } },
        // 56 dp tall for gloves, the bar in its middle.
        ).clickable { onFull(!full) }.padding(vertical = 26.dp),
        contentAlignment = Alignment.Center,
      ) { Box(Modifier.size(40.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))) }
      content()
    } }
  }
}
