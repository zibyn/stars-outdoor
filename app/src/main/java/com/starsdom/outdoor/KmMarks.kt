package com.starsdom.outdoor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.IconTextFit
import org.maplibre.compose.expressions.value.SymbolPlacement
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.DpPadding
import org.maplibre.compose.util.ImageStretch
import kotlin.math.cos
import kotlin.math.pow

// 里程标注 (mvp §2.7): whole kilometres along the 参考轨迹 from its start, thinned as you zoom out.

/** Metres one dp covers at [zoom] (MapLibre's 512 dp world at z0). */
fun metresPerDp(zoom: Double, lat: Double) = 40_075_016.686 * cos(Math.toRadians(lat)) / (512 * 2.0.pow(zoom))

/** Kilometres between marks: every 1 km once a kilometre is 45 dp on screen, every 5 km from 12 dp, else 10. */
fun kmStep(dpPerKm: Double): Int = if (dpPerKm >= 45) 1 else if (dpPerKm >= 12) 5 else 10

/** [kmStep] on the map at [zoom]. */
fun kmStep(zoom: Double, lat: Double): Int = kmStep(1000 / metresPerDp(zoom, lat))

/** Marks less than this apart on screen are one (「3 / 14」); 「终」 this close to 「起」 isn't drawn. */
private const val MERGE_DP = 22

/** Where each whole kilometre falls, none within 150 m of the end (the 「终」 is there). Gaps between segments add nothing, as in [trackStats]. */
fun kmPoints(segments: List<List<TrackPoint>>): List<Pair<Int, TrackPoint>> {
  val at = mutableListOf<Pair<Int, TrackPoint>>()
  var start = 0.0
  for (seg in segments) for (i in 1 until seg.size) {
    val a = seg[i - 1]
    val b = seg[i]
    val length = haversine(a, b)
    while ((at.size + 1) * 1000.0 <= start + length) {
      val t = ((at.size + 1) * 1000.0 - start) / length
      at += at.size + 1 to TrackPoint(0, a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon), null)
    }
    start += length
  }
  return at.filter { it.first * 1000.0 < start - 150 }
}

data class KmMark(val label: String, val lat: Double, val lon: Double)

/** Every [stepKm] of [kmPoints]; marks closer than [mergeM] on the ground are one, as on an out-and-back: 「3 / 14」. */
fun kmMarks(kmPoints: List<Pair<Int, TrackPoint>>, stepKm: Int, mergeM: Double): List<KmMark> {
  // ponytail: greedy, against each group's first mark, O(n²); fine for tens of marks, a grid if tracks get hundreds.
  val merged = mutableListOf<Pair<MutableList<Int>, TrackPoint>>()
  for ((km, p) in kmPoints.filter { it.first % stepKm == 0 }) {
    merged.firstOrNull { haversine(it.second, p) < mergeM }?.first?.add(km) ?: merged.add(mutableListOf(km) to p)
  }
  return merged.map { (kms, p) -> KmMark(kms.joinToString(" / "), p.lat, p.lon) }
}

private fun points(marks: List<KmMark>) = buildJsonObject {
  put("type", "FeatureCollection")
  put("features", buildJsonArray {
    for (m in marks) add(buildJsonObject {
      put("type", "Feature")
      put("geometry", buildJsonObject { put("type", "Point"); put("coordinates", buildJsonArray { add(m.lon); add(m.lat) }) })
      put("properties", buildJsonObject { put("label", m.label) })
    })
  })
}.toString()

/** A [fill] ([Semantic.stroke]) pill with a [color] edge, stretched round the km number. */
internal class PlatePainter(private val color: Color, private val fill: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() {
    val edge = 2.dp.toPx()
    val r = CornerRadius(size.height / 2)
    drawRoundRect(fill, cornerRadius = r)
    drawRoundRect(color, Offset(edge / 2, edge / 2), Size(size.width - edge, size.height - edge), r, Stroke(edge))
  }
}

/** A [color] ([Semantic.stroke]) chevron pointing along the line. */
private class ArrowPainter(private val color: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() {
    val w = 2.dp.toPx()
    drawLine(color, Offset(size.width * 0.3f, size.height * 0.2f), Offset(size.width * 0.7f, size.height / 2), w, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.7f, size.height / 2), Offset(size.width * 0.3f, size.height * 0.8f), w, StrokeCap.Round)
  }
}

/**
 * 里程标注 on the track drawn as [line] (the 参考轨迹, or the one in 轨迹详情), layers named after [id]: direction
 * arrows along it, km plates at [zoom], 「起」 and 「终」
 * (not when it would sit on 「起」, as on a loop). Draw it over the line.
 */
@Composable
fun KmMarkLayers(id: String, segments: List<List<TrackPoint>>, line: String, color: Color, zoom: Double) {
  val ends = segments.filter { it.isNotEmpty() }
  if (ends.isEmpty()) return
  val first = ends.first().first()
  val last = ends.last().last()
  val dp = metresPerDp(zoom, first.lat)
  val stroke = semantic.stroke
  SymbolLayer(
    id = "$id-arrows",
    source = rememberGeoJsonSource(GeoJsonData.JsonString(line)),
    placement = const(SymbolPlacement.Line),
    spacing = const(120.dp),
    iconImage = image(remember(stroke) { ArrowPainter(stroke) }, DpSize(12.dp, 12.dp)),
  )
  val kms = remember(segments) { kmPoints(segments) }
  val marks = remember(kms, zoom) { points(kmMarks(kms, kmStep(zoom, first.lat), mergeM = MERGE_DP * dp)) }
  SymbolLayer(
    id = "$id-km",
    source = rememberGeoJsonSource(GeoJsonData.JsonString(marks)),
    // 20 dp high, the round ends outside the text; only the width fits it.
    iconImage = image(remember(color, stroke) { PlatePainter(color, stroke) }, DpSize(20.dp, 20.dp), stretch = ImageStretch.capInsets(7.dp, 0.dp, 7.dp, 0.dp)),
    iconTextFit = const(IconTextFit.Width),
    iconAllowOverlap = const(true),
    textField = format(span(feature["label"].asString())),
    textFont = const(listOf("Noto Sans Regular")),
    textSize = const(12.sp),
    textColor = const(MaterialTheme.colorScheme.onSurface),
    textAllowOverlap = const(true),
  )
  // 起 green, 终 dark; each with its on-colour text.
  val colors = MaterialTheme.colorScheme
  val flags = listOfNotNull(
    Triple(KmMark("起", first.lat, first.lon), colors.primary, colors.onPrimary),
    Triple(KmMark("终", last.lat, last.lon), colors.inverseSurface, colors.inverseOnSurface).takeUnless { haversine(first, last) < MERGE_DP * dp },
  )
  for ((mark, fill, text) in flags) SymbolLayer(
    id = "$id-${mark.label}",
    source = rememberGeoJsonSource(GeoJsonData.JsonString(remember(mark) { points(listOf(mark)) })),
    iconImage = image(remember(fill, stroke) { EndPainter(fill, stroke) }, DpSize(26.dp, 26.dp)),
    iconAllowOverlap = const(true),
    textField = format(span(feature["label"].asString())),
    textFont = const(listOf("Noto Sans Regular")),
    textSize = const(12.sp),
    textColor = const(text),
    textAllowOverlap = const(true),
  )
}

/** [waypoints] but those under a 「起」 or 「终」 of [tracks] at [zoom]; zoomed in far enough apart, they come back. */
fun clearOfEnds(waypoints: List<Waypoint>, tracks: List<List<List<TrackPoint>>>, zoom: Double): List<Waypoint> {
  val ends = tracks.flatMap { segments -> segments.filter { it.isNotEmpty() }.let { listOfNotNull(it.firstOrNull()?.first(), it.lastOrNull()?.last()) } }
  if (ends.isEmpty()) return waypoints
  val near = MERGE_DP * metresPerDp(zoom, ends.first().lat)
  return waypoints.filter { w -> ends.none { haversine(it, TrackPoint(0, w.lat, w.lon, null)) < near } }
}

/** A [fill] dot with an [edge] ([Semantic.stroke]), for 「起」 and 「终」. */
private class EndPainter(private val fill: Color, private val edge: Color) : Painter() {
  override val intrinsicSize = Size.Unspecified
  override fun DrawScope.onDraw() {
    drawCircle(edge)
    drawCircle(fill, size.minDimension / 2 - 2.5.dp.toPx())
  }
}
