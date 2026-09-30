package com.starsdom.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Shown on launch when a recording was killed before it ended (§2.5). */
@Composable
fun RecoveryPrompt(onContinue: () -> Unit, onFinish: () -> Unit) = Prompt("上次的记录中断了", "已记下的点都在。") {
  Button("继续记录", primary = true, onContinue)
  Button("结束并保存", primary = false, onFinish)
}

/**
 * 强制升级 (#118), the one prompt for every online feature ([ClientOutdated]). [onUpgrade]: 关于 until the
 * in-app update (#51) exists.
 */
@Composable
fun UpgradePrompt(onUpgrade: () -> Unit, onDismiss: () -> Unit) = Prompt(
  "需要升级 App",
  "这个版本太旧，联网功能（在线搜索、队伍、同步、离线包下载、沿途天气）暂时用不了。" +
    "一键求助也要升级后才能用：求助要经过服务器，旧版本发不出去。\n\n离线地图、轨迹记录、标注和本机轨迹照常可用。",
) {
  Button("去升级", primary = true, onUpgrade)
  Button("知道了", primary = false, onDismiss)
}

/** Set once 设为参考 or 沿线下载 was tapped: 轨迹详情 then offers the 出发前 battery row (ux-v2 §4.2). */
const val PREF_BATTERY_DUE = "battery_due"

/** Set once 知道了 was tapped in [BatteryGuide]: counts as 设置过, so the 出发前 row stays gone (ux-v2 §4.2). */
const val PREF_BATTERY_SET = "battery_set"

/**
 * OEM battery savers kill background recording (§2.5): how to stop them. Opened from 轨迹详情's 出发前 row and from
 * 设置, no longer on the first recording (ux-v2 §8).
 */
@Composable
fun BatteryGuide(manufacturer: String, onIgnoreOptimizations: () -> Unit, onAppSettings: () -> Unit, onDismiss: () -> Unit) =
  Prompt("防止手机在后台停掉记录", "锁屏后系统可能为了省电停掉记录。请按以下步骤设置：\n\n" + batteryTip(manufacturer)) {
    Button("关闭电池优化", primary = true, onIgnoreOptimizations)
    Button("打开应用设置", primary = false, onAppSettings)
    Button("知道了", primary = false, onDismiss)
  }

// ponytail: menu paths drift between ROM versions; revisit when users report a brand's path moved.
private fun batteryTip(manufacturer: String) = when (manufacturer.lowercase()) {
  "xiaomi", "redmi", "poco" -> "设置 → 应用设置 → 应用管理 → 星径 → 省电策略 → 无限制；并打开「自启动」。"
  "huawei", "honor" -> "设置 → 电池 → 应用启动管理 → 星径 → 关闭「自动管理」，打开「允许后台活动」。"
  "oppo", "realme", "oneplus" -> "设置 → 电池 → 应用耗电管理 → 星径 → 打开「允许后台运行」和「允许自启动」。"
  "vivo", "iqoo" -> "设置 → 电池 → 后台耗电管理 → 星径 → 允许后台高耗电。"
  else -> "在系统设置中允许星径在后台运行，并不受电池优化限制。"
} + "\n\n另外请在最近任务里锁定本应用，避免一键清理时被关闭。"

@Composable
private fun Prompt(title: String, body: String, buttons: @Composable () -> Unit) {
  // Scrim swallows taps so the map underneath can't be used while the prompt is open.
  Box(Modifier.fillMaxSize().background(Color(0x88000000)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
    Column(Modifier.padding(24.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(20.dp)) {
      BasicText(title, style = TextStyle(fontSize = 18.sp))
      BasicText(body, Modifier.padding(vertical = 12.dp))
      buttons()
    }
  }
}

@Composable
internal fun Button(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) = BasicText(
  text,
  modifier.background(if (primary) Color(0xFF2F9E6E) else Color(0xFFEEEEEE), RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp),
  style = TextStyle(color = if (primary) Color.White else Color.Black, textAlign = TextAlign.Center),
)

/** 再点一次 (ux-v2 §6.3): the first tap turns [label] into [armed] in place; only a second tap within 3 s does it. */
@Composable
internal fun TapAgain(label: String, armed: String, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
  var ready by remember { mutableStateOf(false) }
  LaunchedEffect(ready) { if (ready) { delay(3_000); ready = false } }
  BasicText(
    if (ready) armed else label,
    modifier.heightIn(min = 56.dp).clickable { if (ready) { ready = false; onConfirm() } else ready = true }.padding(horizontal = 12.dp).wrapContentHeight(),
    style = TextStyle(color = Color(0xFFE4572E)),
  )
}
