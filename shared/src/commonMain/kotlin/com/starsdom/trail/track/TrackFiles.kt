package com.starsdom.trail.track

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Points and 标注 without a time (planned routes, plain KML/GeoJSON lines) carry timeMs 0; writers leave <time> out for them.

/** §2.6: single imported file is capped at 50 MB. */
const val MAX_TRACK_FILE_BYTES = 50L * 1024 * 1024

/** One track of an imported file; [planned] for a GPX `<rte>`; [source] the watch a FIT came from (「来自 佳明 fēnix 7」). */
data class ParsedTrack(val name: String, val planned: Boolean, val segments: List<List<TrackPoint>>, val source: String? = null)

/** [waypoints] have id 0 and no track; their [Waypoint.photo] is a path inside the zip, found in [photos]. */
class TrackFile(val tracks: List<ParsedTrack>, val waypoints: List<Waypoint>, val photos: Map<String, ByteArray> = emptyMap())

/**
 * GPX, KML/KMZ (incl. 奥维 .ovkml/.ovkmz), FIT, GeoJSON, PLT, or our own zip export — told apart by content, not
 * by name, since files arriving from WeChat or QQ often lose or mangle their extension. Throws if unreadable.
 */
fun parseTrackFile(bytes: ByteArray): TrackFile = parseAny(bytes).let { f ->
  // A FIT/PLT without positions yields a track with no points; that's not a track.
  TrackFile(f.tracks.filter { t -> t.segments.any { it.isNotEmpty() } }, f.waypoints, f.photos)
}

private fun parseAny(bytes: ByteArray): TrackFile {
  val head = latin1(bytes, 0, 512).trimStart('ï', '»', '¿', ' ', '\t', '\r', '\n')
  return when {
    head.startsWith("PK") -> parseZip(bytes)
    bytes.size >= 12 && latin1(bytes, 8, 4) == ".FIT" -> parseFit(bytes)
    head.startsWith("OziExplorer") -> parsePlt(pltText(bytes))
    head.startsWith("{") -> parseGeoJson(bytes.decodeToString())
    head.startsWith("<") -> parseXml(bytes)
    else -> error("unknown track file")
  }
}

/** Up to [length] bytes from [from], a char each, to sniff a file's kind. */
private fun latin1(bytes: ByteArray, from: Int, length: Int) =
  (from until minOf(bytes.size, from + length)).map { (bytes[it].toInt() and 0xFF).toChar() }.joinToString("")

private fun parseZip(bytes: ByteArray): TrackFile {
  var main: ByteArray? = null
  val others = mutableMapOf<String, ByteArray>()
  for ((name, data) in unzip(bytes, MAX_TRACK_FILE_BYTES)) {
    if (main == null && name.substringAfterLast('.').lowercase() in setOf("gpx", "kml", "ovkml")) main = data else others[name] = data
  }
  val file = parseTrackFile(checkNotNull(main) { "no GPX/KML in zip" })
  return TrackFile(file.tracks, file.waypoints, others)
}

/**
 * The files in a zip, in order, directories left out. What's inflated stops at [limit] bytes in all (else it throws),
 * so a small zip bomb can't exhaust memory.
 */
internal expect fun unzip(bytes: ByteArray, limit: Long): List<Pair<String, ByteArray>>

/** A FIT's records as one track, with [ParsedTrack.source] from its file_id. */
internal expect fun parseFit(bytes: ByteArray): TrackFile

/** [bytes] as text in [charset] (an IANA name, e.g. GBK), for what isn't UTF-8. */
internal expect fun decode(bytes: ByteArray, charset: String): String

private fun parseXml(bytes: ByteArray): TrackFile {
  val handler = XmlHandler()
  readXml(xmlText(bytes), handler)
  check(handler.root == "gpx" || handler.root == "kml") { "not GPX or KML" }
  return TrackFile(handler.tracks, handler.waypoints)
}

/** UTF-8, unless the declaration names another encoding (GBK from Chinese tools). */
private fun xmlText(bytes: ByteArray): String {
  val charset = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([^"']+)""").find(latin1(bytes, 0, 200).removePrefix("ï»¿"))?.groupValues?.get(1)
  return if (charset == null || charset.equals("UTF-8", ignoreCase = true)) bytes.decodeToString().removePrefix("\uFEFF") else decode(bytes, charset)
}

private val startTag = Regex("""<([^\s/>]+)((?:\s+[^\s=/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(/?)>""")
private val attribute = Regex("""([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private val entity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);""")

/**
 * Just the XML GPX and KML need, as SAX would hand it over: elements by local name (namespace prefixes dropped),
 * attributes, text with entities and CDATA. A DTD is skipped, never read, so a file can't make it fetch anything
 * (XXE). Throws on tags that don't match up.
 */
// ponytail: no UTF-16 files and no entities a DTD declares; neither has turned up in GPX or KML.
private fun readXml(text: String, h: XmlHandler) {
  val open = ArrayDeque<String>()
  var i = 0
  fun skipPast(end: String, from: Int) = text.indexOf(end, from).also { check(it >= 0) { "unclosed $end" } } + end.length
  while (true) {
    val lt = text.indexOf('<', i)
    if (lt < 0) { check(text.substring(i).isBlank() && open.isEmpty()) { "unclosed XML" }; return }
    if (lt > i) h.text(unescape(text.substring(i, lt)))
    i = when {
      text.startsWith("<!--", lt) -> skipPast("-->", lt + 4)
      text.startsWith("<![CDATA[", lt) -> skipPast("]]>", lt + 9).also { h.text(text.substring(lt + 9, it - 3)) }
      text.startsWith("<?", lt) -> skipPast("?>", lt + 2)
      // <!DOCTYPE …>, maybe with an internal subset in [ ]: skipped whole.
      text.startsWith("<!", lt) -> {
        val bracket = text.indexOf('[', lt).takeIf { it >= 0 && it < text.indexOf('>', lt) }
        skipPast(">", if (bracket != null) skipPast("]", bracket) else lt)
      }
      text.startsWith("</", lt) -> {
        val gt = skipPast(">", lt)
        val name = text.substring(lt + 2, gt - 1).trim()
        check(open.removeLastOrNull() == name) { "mismatched </$name>" }
        h.end(name.substringAfter(':'))
        gt
      }
      else -> {
        val m = checkNotNull(startTag.matchAt(text, lt)) { "bad tag" }
        val (name, attrs, empty) = m.destructured
        h.start(name.substringAfter(':'), attribute.findAll(attrs).associate { a -> a.groupValues[1].substringAfter(':') to unescape(a.groupValues[2].ifEmpty { a.groupValues[3] }) })
        if (empty.isEmpty()) open.addLast(name) else h.end(name.substringAfter(':'))
        m.range.last + 1
      }
    }
  }
}

private fun unescape(s: String) = if ('&' !in s) s else entity.replace(s) { m ->
  when (val e = m.groupValues[1]) {
    "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "quot" -> "\""; "apos" -> "'"
    else -> (if (e[1] == 'x') e.drop(2).toInt(16) else e.drop(1).toInt()).let { code ->
      if (code > 0xFFFF) (code - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
      else code.toChar().toString()
    }
  }
}

/** GPX and KML in one pass: their element names don't collide where it matters (each read relative to its parent). */
private class XmlHandler {
  val tracks = mutableListOf<ParsedTrack>()
  val waypoints = mutableListOf<Waypoint>()
  var root: String? = null
  private val stack = ArrayDeque<String>()
  private val text = StringBuilder()
  // The feature being read: a GPX wpt/trk/rte or a KML Placemark.
  private var name = ""
  private var desc = ""
  private var time = 0L
  private var link: String? = null
  private var segments = mutableListOf<List<TrackPoint>>()
  private var seg = mutableListOf<TrackPoint>()
  // The point being read: a GPX wpt/trkpt/rtept, a KML Point.
  private var lat = 0.0
  private var lon = 0.0
  private var ele: Double? = null
  private var ptTime = 0L
  private var ptName = ""
  private var point = false
  private val whens = mutableListOf<Long>()

  fun start(local: String, attrs: Map<String, String>) {
    if (root == null) root = local
    text.setLength(0)
    when (local) {
      "wpt", "trkpt", "rtept" -> {
        lat = attrs.getValue("lat").toDouble()
        lon = attrs.getValue("lon").toDouble()
        ele = null; ptTime = 0L; ptName = ""; desc = ""; link = null
      }
      "trk", "rte", "Placemark" -> { name = ""; desc = ""; time = 0L; point = false; segments = mutableListOf(); seg = mutableListOf() }
      "link" -> if (stack.lastOrNull() == "wpt") link = attrs["href"]
      "Track" -> whens.clear()
    }
    stack.addLast(local)
  }

  fun text(s: String) {
    text.append(s)
  }

  fun end(local: String) {
    stack.removeLast()
    val parent = stack.lastOrNull()
    val s = text.toString().trim()
    when (local) {
      "ele" -> ele = s.toDoubleOrNull()
      "time" -> ptTime = parseTime(s)
      "name" -> when (parent) {
        "wpt", "trkpt", "rtept" -> ptName = s
        "trk", "rte", "Placemark" -> name = s
      }
      "desc", "description" -> desc = s
      "wpt" -> waypoints += Waypoint(0, null, ptTime, lat, lon, ele, ptName, desc, link)
      "trkpt", "rtept" -> seg += TrackPoint(ptTime, lat, lon, ele)
      "trkseg", "LineString" -> endSegment()
      "trk", "rte", "Placemark" -> {
        endSegment()
        if (segments.isNotEmpty()) tracks += ParsedTrack(name, local == "rte", segments)
        else if (point) waypoints += Waypoint(0, null, time, lat, lon, ele, name, desc, null)
      }
      "when" -> if (parent == "Track") whens += parseTime(s) else time = parseTime(s)
      "coord" -> s.split(Regex("\\s+")).let { c -> seg += TrackPoint(whens.getOrElse(seg.size) { 0L }, c[1].toDouble(), c[0].toDouble(), c.getOrNull(2)?.toDoubleOrNull()) }
      "Track" -> endSegment()
      "coordinates" -> {
        val points = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tuple ->
          val c = tuple.split(',')
          TrackPoint(0, c[1].toDouble(), c[0].toDouble(), c.getOrNull(2)?.toDoubleOrNull())
        }
        if (parent == "Point") points.firstOrNull()?.let { point = true; lat = it.lat; lon = it.lon; ele = it.ele } else seg += points
      }
    }
    text.setLength(0)
  }

  private fun endSegment() {
    if (seg.isNotEmpty()) segments += seg
    seg = mutableListOf()
  }
}

private fun parseGeoJson(text: String): TrackFile {
  val tracks = mutableListOf<ParsedTrack>()
  val waypoints = mutableListOf<Waypoint>()
  fun point(c: JsonElement, time: Long = 0): TrackPoint = c.jsonArray.let { TrackPoint(time, it[1].jsonPrimitive.double, it[0].jsonPrimitive.double, it.getOrNull(2)?.jsonPrimitive?.double) }
  fun line(c: JsonElement) = c.jsonArray.map { point(it) }
  fun feature(geometry: JsonObject, props: JsonObject) {
    fun prop(vararg keys: String) = keys.firstNotNullOfOrNull { props[it]?.takeIf { v -> v !is JsonNull }?.jsonPrimitive?.contentOrNull }.orEmpty()
    val coords = geometry["coordinates"] ?: return
    when (geometry["type"]?.jsonPrimitive?.content) {
      "Point" -> point(coords, parseTime(prop("time"))).let { waypoints += Waypoint(0, null, it.timeMs, it.lat, it.lon, it.ele, prop("name"), prop("desc", "description"), null) }
      "LineString" -> tracks += ParsedTrack(prop("name"), false, listOf(line(coords)))
      "MultiLineString" -> tracks += ParsedTrack(prop("name"), false, coords.jsonArray.map(::line))
    }
  }
  val root = Json.parseToJsonElement(text).jsonObject
  when (root["type"]?.jsonPrimitive?.content) {
    "FeatureCollection" -> root["features"]!!.jsonArray.forEach { f -> f.jsonObject["geometry"]?.takeIf { it !is JsonNull }?.let { feature(it.jsonObject, f.jsonObject.props()) } }
    "Feature" -> feature(root["geometry"]!!.jsonObject, root.props())
    else -> feature(root, JsonObject(emptyMap()))
  }
  return TrackFile(tracks, waypoints)
}

private fun JsonObject.props() = (this["properties"] as? JsonObject) ?: JsonObject(emptyMap())

/** OziExplorer track: 6 header lines, then `lat,lon,newSegment,altFeet,delphiDays,…`; -777 means no altitude. */
private fun parsePlt(text: String): TrackFile {
  val lines = text.lines()
  val segments = mutableListOf<MutableList<TrackPoint>>()
  for (line in lines.drop(6)) {
    val f = line.split(',').map { it.trim() }
    if (f.size < 5) continue
    val days = f[4].toDoubleOrNull() ?: 0.0
    val alt = f[3].toDoubleOrNull()?.takeIf { it != -777.0 }?.let { it * 0.3048 }
    val p = TrackPoint(if (days > 0) ((days - 25569) * 86_400_000).toLong() else 0L, f[0].toDouble(), f[1].toDouble(), alt)
    if (f[2] == "1" || segments.isEmpty()) segments += mutableListOf<TrackPoint>()
    segments.last() += p
  }
  val name = lines.getOrNull(4)?.split(',')?.getOrNull(3)?.trim().orEmpty()
  return TrackFile(listOf(ParsedTrack(name, false, segments)), emptyList())
}

/** PLT is written by Windows tools, often in GBK; read as that when the bytes aren't valid UTF-8. */
private fun pltText(bytes: ByteArray) = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse { decode(bytes, "GBK") }

private fun parseTime(s: String): Long = runCatching {
  runCatching { Instant.parse(s) }.getOrElse { LocalDateTime.parse(s).toInstant(TimeZone.UTC) }.toEpochMilliseconds()
}.getOrDefault(0L)

private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** As BigDecimal's toPlainString: never 1.0E-5, but 0.000010. */
private fun Double.plain(): String {
  val s = toString()
  val e = s.indexOfFirst { it == 'E' || it == 'e' }.takeIf { it >= 0 } ?: return s
  val sign = if (s.startsWith('-')) "-" else ""
  val mantissa = s.substring(sign.length, e)
  val digits = mantissa.replace(".", "")
  val point = (mantissa.indexOf('.').takeIf { it >= 0 } ?: mantissa.length) + s.substring(e + 1).toInt()
  return sign + when {
    point <= 0 -> "0." + "0".repeat(-point) + digits
    point >= digits.length -> digits + "0".repeat(point - digits.length)
    else -> digits.substring(0, point) + "." + digits.substring(point)
  }
}

/**
 * One `<trkseg>` per recorded segment (split at pause/resume); the track's 标注 as `<wpt>`s.
 * A 标注's [Waypoint.photo] is written as its `<link>`, so the caller passes a path inside the zip or null.
 */
fun toGpx(name: String, segments: List<List<TrackPoint>>, waypoints: List<Waypoint> = emptyList(), planned: Boolean = false): String = buildString {
  append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
  append("<gpx version=\"1.1\" creator=\"Stars Trail\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
  // Only 标注 (a 标注组's export, #72): no empty <trk>, which would come back as a track; the name goes in <metadata>.
  if (segments.isEmpty() && !planned) append("<metadata><name>").append(escapeXml(name)).append("</name></metadata>\n")
  // GPX 1.1 order: metadata, wpt, then rte/trk; inside wpt: ele, time, name, desc, link.
  for (w in waypoints) {
    append("<wpt lat=\"${w.lat.plain()}\" lon=\"${w.lon.plain()}\">")
    w.ele?.let { append("<ele>${it.plain()}</ele>") }
    if (w.timeMs != 0L) append("<time>${Instant.fromEpochMilliseconds(w.timeMs)}</time>")
    if (w.name.isNotEmpty()) append("<name>${escapeXml(w.name)}</name>")
    if (w.description.isNotEmpty()) append("<desc>${escapeXml(w.description)}</desc>")
    w.photo?.let { append("<link href=\"${escapeXml(it)}\"/>") }
    append("</wpt>\n")
  }
  // A planned track goes back out as the <rte> it came in as; a route has no segments, so they're joined.
  if (planned) {
    append("<rte><name>").append(escapeXml(name)).append("</name>\n")
    for (p in segments.flatten()) {
      append("<rtept lat=\"${p.lat.plain()}\" lon=\"${p.lon.plain()}\">")
      p.ele?.let { append("<ele>${it.plain()}</ele>") }
      append("</rtept>\n")
    }
    return@buildString append("</rte>\n</gpx>\n").let {}
  }
  if (segments.isEmpty()) return@buildString append("</gpx>\n").let {}
  append("<trk><name>").append(escapeXml(name)).append("</name>")
  for (seg in segments) {
    append("<trkseg>\n")
    for (p in seg) {
      append("<trkpt lat=\"${p.lat.plain()}\" lon=\"${p.lon.plain()}\">")
      p.ele?.let { append("<ele>${it.plain()}</ele>") }
      if (p.timeMs != 0L) append("<time>${Instant.fromEpochMilliseconds(p.timeMs)}</time>")
      append("</trkpt>\n")
    }
    append("</trkseg>")
  }
  append("</trk>\n</gpx>\n")
}

// ponytail: plain LineStrings drop point times (GPX keeps them); write gx:Track if KML users need times.
/** 标注 as Point Placemarks, the track (if any) as one Placemark with a LineString per segment. Photos aren't included. */
fun toKml(name: String, segments: List<List<TrackPoint>>, waypoints: List<Waypoint>): String = buildString {
  fun coord(lat: Double, lon: Double, ele: Double?) = "${lon.plain()},${lat.plain()}" + (ele?.let { ",${it.plain()}" } ?: "")
  append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
  append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>${escapeXml(name)}</name>\n")
  for (w in waypoints) {
    append("<Placemark><name>${escapeXml(w.name)}</name>")
    if (w.description.isNotEmpty()) append("<description>${escapeXml(w.description)}</description>")
    if (w.timeMs != 0L) append("<TimeStamp><when>${Instant.fromEpochMilliseconds(w.timeMs)}</when></TimeStamp>")
    append("<Point><coordinates>${coord(w.lat, w.lon, w.ele)}</coordinates></Point></Placemark>\n")
  }
  if (segments.isNotEmpty()) {
    append("<Placemark><name>${escapeXml(name)}</name><MultiGeometry>\n")
    for (seg in segments) append("<LineString><coordinates>").append(seg.joinToString(" ") { coord(it.lat, it.lon, it.ele) }).append("</coordinates></LineString>\n")
    append("</MultiGeometry></Placemark>\n")
  }
  append("</Document></kml>\n")
}

/** An exported file's name (#146): the track's, less what file systems or share targets won't take, and not too long. */
fun exportFileName(name: String, extension: String): String =
  name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]"""), "_").trim().take(80).ifEmpty { "轨迹" } + ".$extension"
