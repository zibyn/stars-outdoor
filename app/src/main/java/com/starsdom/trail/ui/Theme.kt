package com.starsdom.trail.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starsdom.trail.recording.DrawerTopLine
import kotlinx.coroutines.delay

// ux-v3 §2 设计令牌. The only UI colour literals (the terrain map's are its style.json and style-dark.tsv): everything else
// takes MaterialTheme roles or [semantic].

/** §2.3; *hand-picked* values as the spec, the rest generated from #2E6A4A (TonalSpot) and a warm grey surface. */
internal val Light = lightColorScheme(
  primary = Color(0xFF2E6A4A), onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFFCDE8D6), onPrimaryContainer = Color(0xFF0F2E1D),
  secondaryContainer = Color(0xFFD1E8D6), onSecondaryContainer = Color(0xFF374B3E),
  background = Color(0xFFF8F6F0), onBackground = Color(0xFF1C1E1A),
  surface = Color(0xFFF8F6F0), onSurface = Color(0xFF1C1E1A),
  surfaceVariant = Color(0xFFE5E2DA), onSurfaceVariant = Color(0xFF464C45),
  surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F3EB),
  surfaceContainer = Color(0xFFEEEBE2), surfaceContainerHigh = Color(0xFFEBE8DF), surfaceContainerHighest = Color(0xFFE5E2DA),
  outline = Color(0xFF787869), outlineVariant = Color(0xFFC6C8BC),
  error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
  // Not in §2.3: the 提示条's inverted bar, the other theme's surface.
  inverseSurface = Color(0xFF31312B), inverseOnSurface = Color(0xFFF4F0E8), inversePrimary = Color(0xFF94D5AE),
)

internal val Dark = darkColorScheme(
  primary = Color(0xFF94D5AE), onPrimary = Color(0xFF003920),
  primaryContainer = Color(0xFF1D5236), onPrimaryContainer = Color(0xFFB0F1C9),
  secondaryContainer = Color(0xFF374B3E), onSecondaryContainer = Color(0xFFD1E8D6),
  background = Color(0xFF121411), onBackground = Color(0xFFE3E3DC),
  surface = Color(0xFF121411), onSurface = Color(0xFFE3E3DC),
  surfaceVariant = Color(0xFF35352F), onSurfaceVariant = Color(0xFFC3C8BF),
  surfaceContainerLowest = Color(0xFF0E0E0A), surfaceContainerLow = Color(0xFF1C1C17),
  surfaceContainer = Color(0xFF1F231E), surfaceContainerHigh = Color(0xFF2A2A25), surfaceContainerHighest = Color(0xFF35352F),
  outline = Color(0xFF929182), outlineVariant = Color(0xFF3E443D),
  error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
  inverseSurface = Color(0xFFE3E3DC), inverseOnSurface = Color(0xFF31312B), inversePrimary = Color(0xFF2E6A4A),
)

/** §2.3 语义色: fixed, outside the theme. 危险 is the theme's error and never goes on the map. */
class Semantic(
  /** 提醒 (警示). */
  val warn: Color,
  /** 我的位置. */
  val me: Color,
  /** 记录线. */
  val recording: Color,
  val teammate: Color,
  /** 参考轨迹. */
  val reference: Color,
  /** Around lines and points on the map. */
  val stroke: Color,
  /** 叠加 (§2.4): 玫红, 棕, 橄榄, 石板灰, round and round. */
  val overlays: List<Color>,
  // 天气 chart (not in §2.3; kept from v2, dark ones lifted to read on the dark surface).
  val sun: Color, val rain: Color, val snow: Color, val cloud: Color, val night: Color, val nightShade: Color,
)

val LightSemantic = Semantic(
  warn = Color(0xFF8A5A00), me = Color(0xFF1F6FEB), recording = Color(0xFFD9480F), teammate = Color(0xFF8E44C9),
  reference = Color(0xFF0E7C86), stroke = Color(0xFFFFFFFF),
  overlays = listOf(Color(0xFFC2185B), Color(0xFF8D5524), Color(0xFF6B6B00), Color(0xFF5F6B73)),
  sun = Color(0xFFF2A516), rain = Color(0xFF2F7FD8), snow = Color(0xFF4FA3D1), cloud = Color(0xFF8A96A3),
  night = Color(0xFF5C6BC0), nightShade = Color(0xFFE8ECF2),
)

val DarkSemantic = Semantic(
  warn = Color(0xFFF2C062), me = Color(0xFF6EA8FF), recording = Color(0xFFFF8A50), teammate = Color(0xFFC792F0),
  reference = Color(0xFF4FD0D9), stroke = Color(0xFF121411),
  overlays = listOf(Color(0xFFFF8FB8), Color(0xFFD9A273), Color(0xFFC8C85A), Color(0xFFAAB6BE)),
  sun = Color(0xFFF2C062), rain = Color(0xFF6EA8FF), snow = Color(0xFF8CCBEB), cloud = Color(0xFFA9B4BF),
  night = Color(0xFF9FA8DA), nightShade = Color(0xFF262A30),
)

/** The colour of the track overlaid [n]th ([overlay]). */
fun Semantic.overlay(n: Int) = overlays[n % overlays.size]

val semantic: Semantic
  @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DarkSemantic else LightSemantic

/** §2.5: six sizes (36 / 24 / 20 / 16 / 14 / 12 sp), the system font, tabular digits everywhere. */
private fun style(size: Int, weight: FontWeight = FontWeight.Normal) =
  TextStyle(fontSize = size.sp, lineHeight = (size * 4 / 3).sp, fontWeight = weight, fontFeatureSettings = "tnum")

private val big = style(36, FontWeight.Bold) // 大数
private val data = style(24, FontWeight.Bold) // 数据
private val title = style(20, FontWeight.Bold) // 标题
private val body = style(16) // 正文
private val secondary = style(14) // 次要
private val label = style(12, FontWeight.Medium) // 标签

// Every M3 role lands on one of the six, so components can't bring their own sizes.
private val Type = Typography(
  displayLarge = big, displayMedium = big, displaySmall = big,
  headlineLarge = data, headlineMedium = data, headlineSmall = data,
  titleLarge = title, titleMedium = body.copy(fontWeight = FontWeight.Medium), titleSmall = secondary.copy(fontWeight = FontWeight.Medium),
  bodyLarge = body, bodyMedium = secondary, bodySmall = label,
  labelLarge = body.copy(fontWeight = FontWeight.Medium), labelMedium = label, labelSmall = label,
)

/** §2.6: 小 12, 中 16, 大 24; 全圆 is CircleShape. */
private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(24.dp),
)

/** §2.7: the 4 dp grid, these steps only. Pages are inset [L]; map overlays sit [M] from the screen edge. */
object Space {
  val XXS = 4.dp
  val XS = 8.dp
  val M = 12.dp
  val L = 16.dp
  val XL = 24.dp
  val XXL = 32.dp
}

/** Light or dark with the system (§1: no dynamic colour); the standard spring motion everywhere (§3.1). */
@Composable
fun AppTheme(content: @Composable () -> Unit) =
  MaterialTheme(if (isSystemInDarkTheme()) Dark else Light, MotionScheme.standard(), AppShapes, Type, content)

/** In dark, raised things get a 1 dp outline instead of relying on a shadow (§2.7). */
@Composable
private fun darkEdge() = if (isSystemInDarkTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

/**
 * A 整页 over the map. Surface swallows every touch inside it, padding included, so nothing below (the map, 底栏,
 * 「开始」) gets one (#138); nor does the 提示条 place itself by what's under it ([HintLayer]). [modifier] goes on the
 * column inside the system bars.
 */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = HintLayer {
  Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding().then(modifier), content = content) }
}

/** A 抽屉 / 小抽屉 from the bottom edge: rounded top, 6 dp shadow; like [Page], it takes every touch inside it. */
@Composable
fun DrawerSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Surface(
  modifier, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), MaterialTheme.colorScheme.surfaceContainerHigh,
  shadowElevation = 6.dp, border = darkEdge(), content = content,
)

/** A [DrawerSurface] of rows, over the navigation bar. */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
  DrawerSurface(modifier.fillMaxWidth().hintAnchor()) {
    Column(Modifier.navigationBarsPadding().padding(Space.L)) {
      DrawerTopLine()
      content()
    }
  }

/** A 地图浮层: solid, a 2 dp shadow (§1 浮层实底, §2.7). */
@Composable
fun Floating(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium, content: @Composable () -> Unit) =
  Surface(modifier, shape, MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 2.dp, border = darkEdge(), content = content)

/** The loading indicator (§3.7): nothing for the first 300 ms, so quick loads don't flash. */
@Composable
fun Spinner(modifier: Modifier = Modifier, strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { delay(300); shown = true }
  if (shown) CircularProgressIndicator(modifier, strokeWidth = strokeWidth)
}
