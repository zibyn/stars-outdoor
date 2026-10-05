package com.starsdom.outdoor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// 提示条 (ux-v3 §6): something that happened once.

/**
 * A 提示条: [text] (出错 ones start with ⚠, [failHint]) and up to two actions, which close it. [sticky] ones wait
 * for an answer; [pick] shows the 在地图上选 cross while it's up. Not a data class: the same text again is a new
 * 提示条 with its own time.
 */
class Hint(val text: String, val actions: List<Pair<String, () -> Unit>> = emptyList(), val sticky: Boolean = false, val pick: Boolean = false)

/**
 * The 提示条 showing first, then the sticky ones waiting, once [next] arrives (null: the first one closed). One at a
 * time: a one-shot covers whatever shows, a sticky one only for a while (it comes back once the one-shot is gone);
 * sticky ones wait their turn in order.
 */
fun queueHint(hints: List<Hint>, next: Hint?): List<Hint> = when {
  next == null -> hints.drop(1)
  next.sticky -> hints + next
  else -> listOf(next) + hints.filter { it.sticky }
}

/** How long [hint] stays: 4 s with only a result, [HINT_LONGEST_MS] with actions, null (until answered) when sticky. */
fun hintMs(hint: Hint): Long? = if (hint.sticky) null else if (hint.actions.isEmpty()) 4_000 else HINT_LONGEST_MS

const val HINT_LONGEST_MS = 8_000L

/** The strip kept free for the 提示条 above the 底栏 / 窄条 (§5.4): its 56 dp and a gap either side. */
val HintStrip = 72.dp

/**
 * The tops of what's at the bottom of the screen (底栏, 抽屉, a page's input row), in root px. Each [Page] has its
 * own [layers] on top, so what it covers no longer counts.
 */
class HintAnchors {
  val tops = mutableStateMapOf<Any, Float>()
  val layers = mutableStateListOf<HintAnchors>()
}

val LocalHintAnchors = staticCompositionLocalOf { HintAnchors() }

/** [content] as a layer of its own over everything before it (a [Page]): only its anchors place the 提示条. */
@Composable
fun HintLayer(content: @Composable () -> Unit) {
  val parent = LocalHintAnchors.current
  val layer = remember { HintAnchors() }
  DisposableEffect(parent) {
    parent.layers += layer
    onDispose { parent.layers -= layer }
  }
  CompositionLocalProvider(LocalHintAnchors provides layer, content = content)
}

/** Marks this as something at the bottom the 提示条 must sit above, never cover (§6 提示条位置). */
@Composable
fun Modifier.hintAnchor(): Modifier {
  val anchors = LocalHintAnchors.current
  val key = remember { Any() }
  DisposableEffect(anchors) { onDispose { anchors.tops.remove(key) } }
  return onGloballyPositioned { val top = it.boundsInRoot().top; if (anchors.tops[key] != top) anchors.tops[key] = top }
}

/**
 * Where the 提示条 shows, over the whole screen: just above the highest [hintAnchor] of the top layer, or above the
 * navigation bar when there's none. It fades in rising 8 dp, and only fades out.
 */
@Composable
fun HintHost(hint: Hint?, onClose: () -> Unit) {
  var height by remember { mutableIntStateOf(0) }
  var layer = LocalHintAnchors.current
  while (layer.layers.isNotEmpty()) layer = layer.layers.last()
  val top = layer.tops.values.minOrNull()
  val density = LocalDensity.current
  val motion = MaterialTheme.motionScheme
  Box(Modifier.fillMaxSize().onSizeChanged { height = it.height }) {
    val place = if (top == null) Modifier.navigationBarsPadding().padding(bottom = Space.M)
    else with(density) { Modifier.padding(bottom = (height - top).coerceAtLeast(0f).toDp() + Space.XS) }
    AnimatedContent(
      hint, Modifier.align(Alignment.BottomCenter).then(place),
      transitionSpec = {
        (fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { with(density) { 8.dp.roundToPx() } }) togetherWith fadeOut(motion.fastEffectsSpec())
      },
    ) { h -> if (h != null) HintBar(h, onClose) }
  }
}

/** The 提示条: one size (§2.5), read out politely when it comes (§4.5). */
@Composable
fun HintBar(hint: Hint, onClose: () -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier.fillMaxWidth().padding(horizontal = Space.M).background(colors.inverseSurface, MaterialTheme.shapes.small).padding(start = Space.L)
      .semantics { liveRegion = LiveRegionMode.Polite },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(hint.text, Modifier.weight(1f).padding(vertical = Space.M), colors.inverseOnSurface, style = MaterialTheme.typography.bodyLarge)
    for ((label, action) in hint.actions) Text(
      label,
      Modifier.heightIn(min = 56.dp).clickable { onClose(); action() }.padding(horizontal = Space.M).wrapContentHeight(),
      colors.inversePrimary,
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}
