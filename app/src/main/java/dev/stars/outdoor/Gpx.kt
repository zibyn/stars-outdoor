package dev.stars.outdoor

import java.time.Instant

fun toGpx(name: String, points: List<TrackPoint>): String = buildString {
  append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
  append("<gpx version=\"1.1\" creator=\"Stars Outdoor\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
  append("<trk><name>").append(name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</name><trkseg>\n")
  for (p in points) {
    append("<trkpt lat=\"${p.lat.toBigDecimal().toPlainString()}\" lon=\"${p.lon.toBigDecimal().toPlainString()}\">")
    if (p.ele != null) append("<ele>${p.ele.toBigDecimal().toPlainString()}</ele>")
    append("<time>${Instant.ofEpochMilli(p.timeMs)}</time></trkpt>\n")
  }
  append("</trkseg></trk>\n</gpx>\n")
}
