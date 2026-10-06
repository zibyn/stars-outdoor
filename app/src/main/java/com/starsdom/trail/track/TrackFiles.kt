package com.starsdom.trail.track

import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

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
  val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart('ï', '»', '¿', ' ', '\t', '\r', '\n')
  return when {
    head.startsWith("PK") -> parseZip(bytes)
    bytes.size >= 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == ".FIT" -> parseFit(bytes)
    head.startsWith("OziExplorer") -> parsePlt(String(bytes, pltCharset(bytes)))
    head.startsWith("{") -> parseGeoJson(String(bytes, Charsets.UTF_8))
    head.startsWith("<") -> parseXml(bytes)
    else -> error("unknown track file")
  }
}

private fun parseZip(bytes: ByteArray): TrackFile {
  var main: ByteArray? = null
  val others = mutableMapOf<String, ByteArray>()
  var total = 0L
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      if (entry.isDirectory) continue
      // The 50 MB cap holds for what's inflated too, so a small zip bomb can't exhaust memory.
      val data = zip.readAtMost(MAX_TRACK_FILE_BYTES - total + 1)
      total += data.size
      check(total <= MAX_TRACK_FILE_BYTES) { "zip over 50 MB inflated" }
      if (main == null && entry.name.substringAfterLast('.').lowercase() in setOf("gpx", "kml", "ovkml")) main = data else others[entry.name] = data
    }
  }
  val file = parseTrackFile(checkNotNull(main) { "no GPX/KML in zip" })
  return TrackFile(file.tracks, file.waypoints, others)
}

/** Reads at most [limit] bytes (InputStream.readNBytes is API 33+ on Android). */
fun java.io.InputStream.readAtMost(limit: Long): ByteArray {
  val out = java.io.ByteArrayOutputStream()
  val buf = ByteArray(64 * 1024)
  while (out.size() < limit) {
    val n = read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
    if (n < 0) break
    out.write(buf, 0, n)
  }
  return out.toByteArray()
}

private fun parseXml(bytes: ByteArray): TrackFile {
  val handler = XmlHandler()
  SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
  check(handler.root == "gpx" || handler.root == "kml") { "not GPX or KML" }
  return TrackFile(handler.tracks, handler.waypoints)
}

/** GPX and KML in one pass: their element names don't collide where it matters (each read relative to its parent). */
private class XmlHandler : DefaultHandler() {
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

  // Never fetch external entities (XXE) from an untrusted file.
  override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

  override fun startElement(uri: String?, local: String, qName: String?, attrs: Attributes) {
    if (root == null) root = local
    text.setLength(0)
    when (local) {
      "wpt", "trkpt", "rtept" -> {
        lat = attrs.getValue("lat").toDouble()
        lon = attrs.getValue("lon").toDouble()
        ele = null; ptTime = 0L; ptName = ""; desc = ""; link = null
      }
      "trk", "rte", "Placemark" -> { name = ""; desc = ""; time = 0L; point = false; segments = mutableListOf(); seg = mutableListOf() }
      "link" -> if (stack.lastOrNull() == "wpt") link = attrs.getValue("href")
      "Track" -> whens.clear()
    }
    stack.addLast(local)
  }

  override fun characters(ch: CharArray, start: Int, length: Int) {
    text.appendRange(ch, start, start + length)
  }

  override fun endElement(uri: String?, local: String, qName: String?) {
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

/** PLT is written by Windows tools, often in GBK; fall back to it when the bytes aren't valid UTF-8. */
private fun pltCharset(bytes: ByteArray) =
  if (runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess) Charsets.UTF_8 else charset("GBK")

// ponytail: FIT records only, as one segment; split at timer stop/start events if paused activities matter.
private fun parseFit(bytes: ByteArray): TrackFile {
  val seg = mutableListOf<TrackPoint>()
  val decode = Decode()
  val broadcaster = MesgBroadcaster(decode)
  var source: String? = null
  broadcaster.addListener(FileIdMesgListener { m -> if (source == null) source = fitSource(m.manufacturer, m.product, m.productName) })
  broadcaster.addListener(RecordMesgListener { r ->
    val lat = r.positionLat ?: return@RecordMesgListener
    val lon = r.positionLong ?: return@RecordMesgListener
    val semicircle = 180.0 / 2147483648.0
    seg += TrackPoint(r.timestamp?.date?.time ?: 0L, lat * semicircle, lon * semicircle, (r.enhancedAltitude ?: r.altitude)?.toDouble())
  })
  check(decode.read(ByteArrayInputStream(bytes), broadcaster)) { "bad FIT" }
  return TrackFile(listOf(ParsedTrack("", false, listOf(seg).filter { it.isNotEmpty() }, source)), emptyList())
}

private val fitMakers = mapOf(Manufacturer.GARMIN to "佳明", Manufacturer.COROS to "高驰", Manufacturer.COROS_BYTE to "高驰", Manufacturer.SUUNTO to "颂拓")

// ponytail: a few families by name prefix; a Garmin outside them shows as just 佳明, add its family here.
private val garminFamilies = listOf(
  "FENIX" to "fēnix", "EPIX" to "epix", "FR" to "Forerunner", "INSTINCT" to "Instinct", "ENDURO" to "Enduro", "TACTIX" to "tactix",
  "MARQ" to "MARQ", "EDGE" to "Edge", "VIVOACTIVE" to "vívoactive", "VENU" to "Venu", "DESCENT" to "Descent", "APPROACH" to "Approach",
)
private val makerPrefix = Regex("^\\s*(coros|suunto)\\s*", RegexOption.IGNORE_CASE)
private val garminVariant = Regex("_(ASIA|APAC|CHINA|CHN|JAPAN|JPN|TAIWAN|TWN|KOREA|KOR|SEA|RUSSIA|SMALL|LARGE|\\d+MM)(?=_|$)")

/** 「来自 佳明 fēnix 7」 from a FIT's file_id; just the maker when the model isn't known, null when the maker isn't. */
private fun fitSource(manufacturer: Int?, product: Int?, productName: String?): String? {
  val maker = fitMakers[manufacturer] ?: return null
  val model = if (manufacturer == Manufacturer.GARMIN) product?.let { garminModel(GarminProduct.getStringFromValue(it)) }
  else productName?.replace(makerPrefix, "")?.trim()?.takeIf { it.isNotEmpty() }
  return listOfNotNull("来自", maker, model).joinToString(" ")
}

/** FENIX7S_PRO_SOLAR_APAC → 「fēnix 7S Pro Solar」: region and case size dropped. */
private fun garminModel(constant: String): String? {
  val name = garminVariant.replace(constant, "")
  val (prefix, family) = garminFamilies.firstOrNull { (p, _) -> name.startsWith(p) && (p != "FR" || name.getOrNull(2)?.isDigit() == true) } ?: return null
  val rest = name.removePrefix(prefix).split('_').filter { it.isNotEmpty() }.map { if (it[0].isDigit()) it else it.lowercase().replaceFirstChar(Char::uppercase) }
  return (listOf(family) + rest).joinToString(" ")
}

private fun parseTime(s: String): Long = runCatching {
  runCatching { OffsetDateTime.parse(s).toInstant() }.getOrElse { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }.toEpochMilli()
}.getOrDefault(0L)

private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun Double.plain() = toBigDecimal().toPlainString()

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
    if (w.ele != null) append("<ele>${w.ele.plain()}</ele>")
    if (w.timeMs != 0L) append("<time>${Instant.ofEpochMilli(w.timeMs)}</time>")
    if (w.name.isNotEmpty()) append("<name>${escapeXml(w.name)}</name>")
    if (w.description.isNotEmpty()) append("<desc>${escapeXml(w.description)}</desc>")
    if (w.photo != null) append("<link href=\"${escapeXml(w.photo)}\"/>")
    append("</wpt>\n")
  }
  // A planned track goes back out as the <rte> it came in as; a route has no segments, so they're joined.
  if (planned) {
    append("<rte><name>").append(escapeXml(name)).append("</name>\n")
    for (p in segments.flatten()) {
      append("<rtept lat=\"${p.lat.plain()}\" lon=\"${p.lon.plain()}\">")
      if (p.ele != null) append("<ele>${p.ele.plain()}</ele>")
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
      if (p.ele != null) append("<ele>${p.ele.plain()}</ele>")
      if (p.timeMs != 0L) append("<time>${Instant.ofEpochMilli(p.timeMs)}</time>")
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
    if (w.timeMs != 0L) append("<TimeStamp><when>${Instant.ofEpochMilli(w.timeMs)}</when></TimeStamp>")
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
  name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(80).ifEmpty { "轨迹" } + ".$extension"
