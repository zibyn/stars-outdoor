package com.starsdom.outdoor

import android.content.Context
import android.provider.Settings
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.time.Duration.Companion.milliseconds

/**
 * §5 动画 durations, ms. Compose transitions already drop to 0 when the system 移除动画 is on; the map
 * camera doesn't, so its animated moves go through [moveCamera]. 按住计时 must not use these.
 */
object Motion {
  /** 快: press feedback, switches, 标注落点, 提示条 fade. */
  const val FAST = 150
  /** 标准: drawers, full pages, 规划 ↔ 活动 fade; decelerate in, accelerate out. */
  const val ENTER = 250
  const val EXIT = 200
  /** 相机: 回到我的位置, 切朝向, 回正. */
  const val CAMERA = 300
  /** Each new fix while 跟随, linear. */
  const val FOLLOW = 1000
  /** Opening 轨迹详情, tapping a 队友 or a 失联 label: fit the target in view. */
  const val FOCUS = 400
}

/** The system 移除动画 (animator duration scale 0). */
fun animationsOff(context: Context) =
  Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Eases the camera to [to] over [ms]; jumps there with 移除动画 on, or when it's more than ~3 screens away. */
suspend fun MapState.moveCamera(context: Context, to: CameraPosition, ms: Int, easing: CubicBezier = CubicBezier.Default) {
  val (sw, ne) = getVisibleBounds() ?: return setCameraPosition(to)
  val from = cameraPosition.target
  val far = abs(to.target.longitude - from.longitude) > 3 * (ne.longitude - sw.longitude) ||
    abs(to.target.latitude - from.latitude) > 3 * (ne.latitude - sw.latitude)
  if (far || animationsOff(context)) return setCameraPosition(to)
  animateCamera(CameraUpdate(to.target, to.zoom, to.bearing, to.tilt, to.padding), CameraAnimation.Ease(ms.milliseconds, easing))
}

/**
 * Where the camera goes to fit the box [west]..[east] × [south]..[north] into a [widthDp] × [heightDp] map, inside
 * the given padding (the drawer's height at the bottom): its target and zoom (16 at most, for a track of one point).
 * maplibre-compose's fitCameraToBounds jumps; this lets 打开轨迹详情 ease there over [Motion.FOCUS].
 */
fun fitCamera(west: Double, south: Double, east: Double, north: Double, widthDp: Double, heightDp: Double, leftDp: Double, topDp: Double, rightDp: Double, bottomDp: Double): Pair<Position, Double> {
  val zoom = log2(minOf((widthDp - leftDp - rightDp) / ((x(east) - x(west)) * 512), (heightDp - topDp - bottomDp) / ((y(south) - y(north)) * 512))).coerceAtMost(16.0)
  val scale = 512 * 2.0.pow(zoom)
  // The screen centre sits off the padded area's centre by half the padding's difference.
  val cx = (x(west) + x(east)) / 2 - (leftDp - rightDp) / 2 / scale
  val cy = (y(north) + y(south)) / 2 - (topDp - bottomDp) / 2 / scale
  return position(cx, cy) to zoom
}

/** The camera target at [zoom] that shows [at] in the middle of what a [bottomDp] drawer leaves of the map. */
fun centreAbove(at: Position, zoom: Double, bottomDp: Double): Position =
  position(x(at.longitude), y(at.latitude) + bottomDp / 2 / (512 * 2.0.pow(zoom)))

// Web Mercator in world units (0..1, y down); a world is 512 dp wide at zoom 0.
private fun x(lon: Double) = (lon + 180) / 360
private fun y(lat: Double) = Math.toRadians(lat).let { (1 - ln(tan(it) + 1 / cos(it)) / PI) / 2 }
private fun position(x: Double, y: Double) = Position(longitude = x * 360 - 180, latitude = Math.toDegrees(atan(sinh(PI * (1 - 2 * y)))))
