package dev.stars.outdoor

import android.content.Context
import android.provider.Settings
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import kotlin.time.Duration.Companion.milliseconds

/**
 * §5 动画 durations, ms. Compose transitions already drop to 0 when the system 移除动画 is on; the map
 * camera doesn't, so camera moves go through [moveCamera]. 按住计时 must not use these.
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

/** Eases the camera to [to] over [ms], or jumps there with 移除动画 on. */
suspend fun MapState.moveCamera(context: Context, to: CameraPosition, ms: Int) {
  if (animationsOff(context)) return setCameraPosition(to)
  animateCamera(CameraUpdate(to.target, to.zoom, to.bearing, to.tilt, to.padding), CameraAnimation.Ease(ms.milliseconds))
}
