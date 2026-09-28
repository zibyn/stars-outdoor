package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown on launch when a recording was killed before it ended (§2.5). */
@Composable
fun RecoveryPrompt(onContinue: () -> Unit, onFinish: () -> Unit) = Prompt("检测到未结束的记录", "记录进程被中断，已记录的点都已保存。") {
  Button("继续记录", primary = true, onContinue)
  Button("结束并保存", primary = false, onFinish)
}

/** Shown once, the first time recording starts: OEM battery savers kill background recording (§2.5). */
@Composable
fun BatteryGuide(manufacturer: String, onIgnoreOptimizations: () -> Unit, onAppSettings: () -> Unit, onDismiss: () -> Unit) =
  Prompt("防止记录被系统中断", "锁屏后系统可能为了省电停止记录。请按以下步骤设置：\n\n" + batteryTip(manufacturer)) {
    Button("关闭电池优化", primary = true, onIgnoreOptimizations)
    Button("打开应用设置", primary = false, onAppSettings)
    Button("知道了", primary = false, onDismiss)
  }

// ponytail: menu paths drift between ROM versions; revisit when users report a brand's path moved.
private fun batteryTip(manufacturer: String) = when (manufacturer.lowercase()) {
  "xiaomi", "redmi", "poco" -> "设置 → 应用设置 → 应用管理 → Stars Outdoor → 省电策略 → 无限制；并打开「自启动」。"
  "huawei", "honor" -> "设置 → 电池 → 应用启动管理 → Stars Outdoor → 关闭「自动管理」，打开「允许后台活动」。"
  "oppo", "realme", "oneplus" -> "设置 → 电池 → 应用耗电管理 → Stars Outdoor → 打开「允许后台运行」和「允许自启动」。"
  "vivo", "iqoo" -> "设置 → 电池 → 后台耗电管理 → Stars Outdoor → 允许后台高耗电。"
  else -> "在系统设置中允许 Stars Outdoor 在后台运行，并不受电池优化限制。"
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
