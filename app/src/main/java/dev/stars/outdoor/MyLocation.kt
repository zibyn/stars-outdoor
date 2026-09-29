package dev.stars.outdoor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CubicBezier
import org.maplibre.compose.location.HeadingMeasurement
import org.maplibre.compose.location.HeadingRequest
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.rememberDefaultHeadingProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.inDegrees
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** 定位按钮 (§3.5): 未跟随 → 跟随 → 朝向 → 跟随. */
enum class Follow(@DrawableRes val icon: Int, val label: String) {
  Off(R.drawable.location_searching_wght500_24px, "定位：未跟随"),
  On(R.drawable.my_location_wght600fill1_24px, "定位：跟随"),
  Heading(R.drawable.navigation_wght600fill1_24px, "定位：朝向");

  val next get() = if (this == On) Heading else On
}

/** Where I am; the compass sensor only runs in 朝向, once a second to match the 1 s camera steps. */
@Composable
fun rememberMyLocation(heading: Boolean): LocationState {
  val compass = rememberDefaultHeadingProvider()
  return rememberLocationState(headingProvider = if (heading) compass else NoHeading, headingRequest = HeadingRequest(1.seconds))
}

private object NoHeading : HeadingProvider {
  override fun updates(request: HeadingRequest) = emptyFlow<HeadingMeasurement>()
}

/**
 * Keeps the camera on me while [follow] (§3.5, §5): 300 ms onto me, then each fix 1 s linear; 跟随 is north-up,
 * 朝向 turns with the phone. [level] (the compass) takes the tilt out on the way in. A drag hands the map back.
 */
@Composable
fun FollowCamera(map: MapState, me: LocationState, follow: Follow, level: Boolean, onLevelled: () -> Unit, onDragged: () -> Unit) {
  val context = LocalContext.current
  LaunchedEffect(follow, level) {
    if (follow == Follow.Off) return@LaunchedEffect
    var first = true
    // Whole degrees, so compass jitter doesn't move the map. Moves chain: each runs out, then goes to the latest.
    snapshotFlow { me.lastLocation?.position?.let { it to if (follow == Follow.Heading) me.lastHeading?.let { h -> (h.bearing - Bearing.North).inDegrees.roundToInt().toDouble() } else 0.0 } }
      .filterNotNull()
      .conflate()
      .collect { (at, bearing) ->
        val c = map.cameraPosition
        val to = c.copy(target = at, bearing = bearing ?: c.bearing, tilt = if (level) 0.0 else c.tilt)
        if (first) map.moveCamera(context, to, Motion.CAMERA) else map.moveCamera(context, to, Motion.FOLLOW, CubicBezier.Linear)
        first = false
        if (level) onLevelled()
      }
  }
  LaunchedEffect(follow) {
    if (follow == Follow.Off) return@LaunchedEffect
    snapshotFlow { map.isCameraMoving && map.cameraMoveReason == CameraMoveReason.GESTURE }.first { it }
    onDragged()
  }
}

/** The last fix, unless older than 30 s (GPS lost): 标注 would store the wrong place, the 状态条 says 正在定位. */
fun LocationState.freshFix() = lastLocation?.takeIf { lastLocationMeasurementMark?.elapsedNow()?.let { it < 30.seconds } == true }

@Composable
fun LocateButton(follow: Follow, onClick: () -> Unit, modifier: Modifier = Modifier) = MapIconButton(follow.icon, follow.label, onClick, modifier)

/** Shows while the map isn't north-up or is tilted (§3.5). */
@Composable
fun Compass(onClick: () -> Unit, modifier: Modifier = Modifier) = MapIconButton(R.drawable.explore_wght500_24px, "指南针：回正", onClick, modifier)

/** A white round map button with the §7 1.5 dp dark translucent edge. */
@Composable
internal fun MapIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) = Box(
  modifier.size(48.dp).border(1.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape).background(Color.White, CircleShape).clip(CircleShape).clickable(onClick = onClick),
  contentAlignment = Alignment.Center,
) {
  Icon(icon, description)
}
