package dev.stars.outdoor

import java.time.Instant

private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun Double.plain() = toBigDecimal().toPlainString()

/** One `<trkseg>` per recorded segment (split at pause/resume); the track's 标注 as `<wpt>`s. */
fun toGpx(name: String, segments: List<List<TrackPoint>>, waypoints: List<Waypoint> = emptyList()): String = buildString {
  append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
  append("<gpx version=\"1.1\" creator=\"Stars Outdoor\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
  // GPX 1.1 order: wpt before trk; inside wpt: ele, time, name, desc.
  for (w in waypoints) {
    append("<wpt lat=\"${w.lat.plain()}\" lon=\"${w.lon.plain()}\">")
    if (w.ele != null) append("<ele>${w.ele.plain()}</ele>")
    append("<time>${Instant.ofEpochMilli(w.timeMs)}</time>")
    if (w.name.isNotEmpty()) append("<name>${escapeXml(w.name)}</name>")
    if (w.description.isNotEmpty()) append("<desc>${escapeXml(w.description)}</desc>")
    append("</wpt>\n")
  }
  append("<trk><name>").append(escapeXml(name)).append("</name>")
  for (seg in segments) {
    append("<trkseg>\n")
    for (p in seg) {
      append("<trkpt lat=\"${p.lat.plain()}\" lon=\"${p.lon.plain()}\">")
      if (p.ele != null) append("<ele>${p.ele.plain()}</ele>")
      append("<time>${Instant.ofEpochMilli(p.timeMs)}</time></trkpt>\n")
    }
    append("</trkseg>")
  }
  append("</trk>\n</gpx>\n")
}
