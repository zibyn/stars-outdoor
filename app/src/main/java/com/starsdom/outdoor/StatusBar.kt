package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 状态条 (ux-v2 §3.6, wording §6.4): only when something is wrong, the most pressing first.

enum class StatusAction { OpenLocation, Permission, Terrain }

data class Status(val text: String, val action: StatusAction? = null)

/**
 * What the 状态条 looks at. [fixAccuracyM] null: no fix; [unsent]: team reports waiting for signal;
 * [sharing]: sharing with a team (its interval drops on low battery); [lastSync] as HH:mm.
 */
data class StatusInput(
  val locationOn: Boolean = true,
  val permitted: Boolean = true,
  val fixAccuracyM: Double? = 5.0,
  val reference: Boolean = false,
  val basemap: Basemap = Basemap.Terrain,
  val online: Boolean = true,
  val unsent: Boolean = false,
  val battery: Int? = 100,
  val sharing: Boolean = false,
  val syncFailed: Boolean = false,
  val lastSync: String? = null,
)

/** The 状态条 lines, most pressing first (§3.6); [active]: 活动状态's short wording, else 规划状态's. */
fun statusLines(active: Boolean, s: StatusInput): List<Status> {
  fun pick(activeText: String, planText: String) = if (active) activeText else planText
  val lines = mutableListOf<Status>()
  if (!s.locationOn) lines += Status(pick("定位已关 · 点这里打开", "定位已关，记录和共享都用不了 · 点这里打开"), StatusAction.OpenLocation)
  if (!s.permitted) lines += Status(pick("没有定位权限 · 点这里开启", "没有定位权限，记录不到轨迹 · 点这里开启"), StatusAction.Permission)
  // No fix follows from the two above: not listed again.
  if (s.locationOn && s.permitted) {
    val accuracy = s.fixAccuracyM
    if (accuracy == null) lines += Status(pick("正在定位", "正在定位，到开阔处更快"))
    // Planning with a 参考轨迹, its bar greys out instead (§3.2).
    else if (accuracy > POOR_FIX_M && (active || !s.reference)) lines += Status(pick("定位不准 · 偏离提醒暂停", "定位不准（约 ${accuracy.roundToInt()} m）"))
  }
  if (!s.online && s.basemap != Basemap.Terrain) lines += Status("${s.basemap.label}图离线用不了 · 切到地形", StatusAction.Terrain)
  if (s.unsent) lines += Status(pick("离线 · 位置联网后补发", "没有信号，你的位置联网后补发给队友"))
  if (!s.online) lines += Status(pick("离线 · 地图照常用", "离线中：已下载的地图和记录照常可用"))
  s.battery?.takeIf { it < 20 }?.let { b ->
    val every = fixedIntervalS(b, saver = false)?.takeIf { s.sharing && !active }?.let { "，共享已降到每 ${it / 60} 分钟一次" }.orEmpty()
    lines += Status("电量 $b%$every")
  }
  if (s.syncFailed && !active) lines += Status("同步没成功，联网后会自动再试" + s.lastSync?.let { " · 上次同步 $it" }.orEmpty())
  return lines
}

/** The first of [lines], with 「+N」 opening the rest; a line with an action runs it when tapped. */
@Composable
fun StatusBar(lines: List<Status>, onAction: (StatusAction) -> Unit, modifier: Modifier = Modifier) {
  if (lines.isEmpty()) return
  // Closed again once down to one line.
  var open by remember(lines.size > 1) { mutableStateOf(false) }
  Column(modifier.fillMaxWidth().background(Color(0xFFFFF4D6), RoundedCornerShape(8.dp))) {
    (if (open) lines else lines.take(1)).forEachIndexed { i, line ->
      Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(line.action?.let { a -> Modifier.clickable { onAction(a) } } ?: Modifier).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicText(line.text, Modifier.weight(1f).padding(vertical = 8.dp), style = TextStyle(fontSize = 15.sp))
        if (i == 0 && lines.size > 1) Box(Modifier.heightIn(min = 48.dp).clickable { open = !open }.padding(start = 12.dp), contentAlignment = Alignment.Center) {
          BasicText(if (open) "收起" else "+${lines.size - 1}", style = TextStyle(color = Color(0xFF2F9E6E), fontSize = 15.sp))
        }
      }
    }
  }
}
