// PROTOTYPE — throwaway spike. Renders local PMTiles (basemap, DEM, contours) offline with
// MapLibre Compose, plus 1000 waypoints and 5 long tracks to judge performance.
// Data files are pushed by push-data.sh into getExternalFilesDir(null).
package dev.stars.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

private const val W = 107.5
private const val S = 33.8
private const val E = 108.05
private const val N = 34.25

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val dir = getExternalFilesDir(null)!!
    writeTestGeoJson(dir)
    // style.json in the data dir overrides the bundled one, so the style can be iterated without rebuilding
    val override = File(dir, "style.json")
    val style = (if (override.exists()) override.readText() else assets.open("style.json").bufferedReader().readText()).replace("__DIR__", dir.absolutePath)
    val status = listOf("basemap.pmtiles", "dem.pmtiles", "contours.pmtiles", "fonts")
      .joinToString("\n") { name ->
        val f = File(dir, name)
        val size = if (f.isDirectory) f.walk().filter { it.isFile }.sumOf { it.length() } else f.length()
        "$name: ${if (f.exists()) "%.1f MB".format(size / 1e6) else "MISSING"}"
      }

    setContent {
      val state = rememberMapState(
        baseStyle = BaseStyle.Json(style),
        initialCameraPosition = CameraPosition(target = Position(latitude = 33.96, longitude = 107.77), zoom = 12.0),
      )
      val scope = rememberCoroutineScope()
      var tilted by remember { mutableStateOf(false) }
      Box(Modifier.fillMaxSize()) {
        MaplibreMap(modifier = Modifier.fillMaxSize(), state = state)
        Column(
          Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0xCCFFFFFF)).padding(8.dp)
        ) {
          BasicText(status)
          BasicText(
            if (tilted) "[ 回正 0° ]" else "[ 倾斜 60° ]",
            Modifier.padding(top = 8.dp).clickable {
              tilted = !tilted
              scope.launch { state.animateCamera(CameraUpdate(tilt = if (tilted) 60.0 else 0.0)) }
            },
          )
        }
      }
    }
  }
}

// 1000 random waypoints + 5 random-walk tracks of 3000 points each inside the sample bbox.
private fun writeTestGeoJson(dir: File) {
  val rnd = Random(42)
  val points = (1..1000).joinToString(",") {
    """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${W + rnd.nextDouble() * (E - W)},${S + rnd.nextDouble() * (N - S)}]}}"""
  }
  File(dir, "waypoints.geojson").writeText("""{"type":"FeatureCollection","features":[$points]}""")
  val tracks = (1..5).joinToString(",") {
    var x = W + 0.1 + rnd.nextDouble() * 0.35
    var y = S + 0.1 + rnd.nextDouble() * 0.25
    val coords = (1..3000).joinToString(",") {
      x = (x + (rnd.nextDouble() - 0.5) * 0.002).coerceIn(W, E)
      y = (y + (rnd.nextDouble() - 0.5) * 0.002).coerceIn(S, N)
      "[$x,$y]"
    }
    """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
  }
  File(dir, "tracks.geojson").writeText("""{"type":"FeatureCollection","features":[$tracks]}""")
}
