package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 提示条 (ux-v2 §5).

/**
 * A 提示条: [text] and its actions, which close it. [sticky] ones wait for an answer; [pick] shows the 在地图上选
 * cross while it's up. Not a data class: the same text again is a new 提示条 with its own time.
 */
class Hint(val text: String, val actions: List<Pair<String, () -> Unit>> = emptyList(), val sticky: Boolean = false, val pick: Boolean = false)

/**
 * The 提示条 showing first, then those waiting, once [next] arrives (null: the first one closed). A new one replaces a
 * plain one showing; behind a [Hint.sticky] one it waits, so the question stays until answered (#113), the newest
 * plain one only.
 */
fun queueHint(hints: List<Hint>, next: Hint?): List<Hint> = when {
  next == null -> hints.drop(1)
  // Stale plain ones (an old 撤销) don't pile up behind it.
  hints.firstOrNull()?.sticky == true -> listOf(hints[0]) + hints.drop(1).filter { it.sticky } + next
  else -> listOf(next) + hints.drop(1)
}

/** §5 提示条停留: 4 s, 6 s with 撤销 / 补充, 8 s in 活动状态. */
fun hintMs(active: Boolean, actions: Boolean): Long = if (active) HINT_LONGEST_MS else if (actions) 6_000 else 4_000

const val HINT_LONGEST_MS = 8_000L

/** The 提示条, big in 活动状态. */
@Composable
fun HintBar(hint: Hint, big: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val size = if (big) 20.sp else 16.sp
  Row(modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color(0xFF2B2F36), RoundedCornerShape(8.dp)).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(hint.text, Modifier.weight(1f).padding(vertical = 12.dp), style = TextStyle(color = Color.White, fontSize = size))
    for ((label, action) in hint.actions) BasicText(
      label,
      Modifier.heightIn(min = 56.dp).clickable { onClose(); action() }.padding(horizontal = 12.dp).wrapContentHeight(),
      style = TextStyle(color = Color(0xFF7FD6AE), fontSize = size),
    )
  }
}
