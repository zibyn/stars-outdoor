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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 强制升级 (#118), the one prompt for every online feature ([ClientOutdated]). [onUpgrade]: 关于 until the
 * in-app update (#51) exists.
 */
@Composable
fun UpgradePrompt(onUpgrade: () -> Unit, onDismiss: () -> Unit) = Prompt(
  "需要升级 App",
  "这个版本太旧，联网功能（在线搜索、队伍、同步、离线包下载、沿途天气）暂时用不了。\n\n离线地图、轨迹记录、标注和本机轨迹照常可用。",
) {
  Button("去升级", primary = true, onUpgrade)
  Button("知道了", primary = false, onDismiss)
}

@Composable
private fun Prompt(title: String, body: String, buttons: @Composable () -> Unit) {
  // Scrim swallows taps so the map underneath can't be used while the prompt is open.
  Box(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null) {},
    contentAlignment = Alignment.Center,
  ) {
    Surface(Modifier.padding(24.dp), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainerHigh) {
      Column(Modifier.padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, Modifier.padding(vertical = 12.dp))
        buttons()
      }
    }
  }
}

@Composable
internal fun Button(text: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) = Text(
  text,
  modifier.background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
    .clip(CircleShape).clickable(onClick = onClick).padding(12.dp),
  if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
)

/**
 * 再点一次 (ux-v2 §6.3): the first tap turns [label] into [armed] in place; only a second tap within 3 s does it.
 * [busy] spins in its place (§7.1 加载).
 */
@Composable
internal fun TapAgain(
  label: String, armed: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false,
  color: Color = MaterialTheme.colorScheme.error, onConfirm: () -> Unit,
) {
  var ready by remember { mutableStateOf(false) }
  LaunchedEffect(ready) { if (ready) { delay(3_000); ready = false } }
  if (busy) return Box(modifier.heightIn(min = 56.dp), contentAlignment = Alignment.Center) { Spinner() }
  Text(
    if (ready) armed else label,
    modifier.heightIn(min = 56.dp).clickable(enabled, role = Role.Button) { if (ready) { ready = false; onConfirm() } else ready = true }
      .padding(horizontal = Space.M).wrapContentHeight(),
    if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
  )
}
