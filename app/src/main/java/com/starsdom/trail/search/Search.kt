package com.starsdom.trail.search

// 搜索 (spec §2.10): coordinates typed in, parsed here; places from the 山名别名表, the offline packages'
// 地名索引 and, online, the server (Photon, 天地图), ranked together.

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.DrawableRes
import com.starsdom.trail.R
import com.starsdom.trail.map.nearestPlace
import com.starsdom.trail.net.model.PlaceDto
import com.starsdom.trail.net.orNull
import com.starsdom.trail.team.bearing
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.dayText
import com.starsdom.trail.track.distanceValue
import com.starsdom.trail.track.haversine
import java.io.File
import kotlin.math.roundToInt

/** A search result; [names] are all it answers to (name, 中文名, English name). */
data class Place(
  val name: String,
  val kind: String,
  val lat: Double,
  val lon: Double,
  val detail: String? = null,
  val importance: Double = 0.0,
  val names: List<String> = listOf(name),
) {
  val point get() = TrackPoint(0, lat, lon, null)
}

/** A place the server found (GET /v1/search). */
fun PlaceDto.toPlace() = Place(name, kind, lat, lon, detail.orNull())

/**
 * 山名别名表 (issue #18): in OSM a mountain is usually its summit's name (泰山 is 玉皇顶), so the
 * mountain's own name finds nothing or a namesake far away. Each line: mountain, summit, lat, lon.
 * The alias is a place of its own that outranks everything else of that name.
 */
fun aliasPlaces(tsv: String): List<Place> = tsv.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
  val (alias, summit, lat, lon) = line.split('\t')
  Place(alias, "peak", lat.toDouble(), lon.toDouble(), detail = "主峰 $summit", importance = 1.0)
}

/**
 * §8.2 第 1 条: exact name matches first, then names starting with [query], then the rest (matched by 拼音 or inside a
 * name); within each, the 山名别名表 first, then nearer to ([lat], [lon]). The same place from two sources (same name and
 * kind within 200 m) shows once.
 */
fun rankPlaces(places: List<Place>, query: String, lat: Double, lon: Double): List<Place> {
  val q = query.trim().lowercase()
  fun level(p: Place) = p.names.map { it.lowercase() }.let { n ->
    when {
      q in n -> 0
      n.any { it.startsWith(q) } -> 1
      else -> 2
    }
  }
  val here = TrackPoint(0, lat, lon, null)
  val ranked = places.sortedWith(compareBy<Place>({ level(it) }, { it.importance < 1.0 }, { haversine(here, it.point) }))
  val kept = mutableListOf<Place>()
  for (p in ranked) if (kept.none { it.name == p.name && it.kind == p.kind && haversine(it.point, p.point) < 200 }) kept += p
  return kept
}

/** A search result's icon (§8.2 第 1 条). */
enum class PlaceCategory(@DrawableRes val icon: Int, val label: String) {
  Peak(R.drawable.landscape_wght500_24px, "山峰"),
  Town(R.drawable.location_city_wght500_24px, "村镇"),
  Water(R.drawable.water_wght500_24px, "水体"),
  Sight(R.drawable.attractions_wght500_24px, "景点"),
}

/** By OSM value (地名索引, Photon) or 天地图's kinds; anything else is a 景点. */
fun placeCategory(kind: String): PlaceCategory = when (kind) {
  "peak", "volcano", "saddle", "ridge", "cliff", "glacier", "valley", "mountain_range", "mountain_pass", "cave_entrance" -> PlaceCategory.Peak
  "water", "spring", "waterfall", "river", "stream", "lake", "reservoir", "bay" -> PlaceCategory.Water
  "city", "town", "village", "hamlet", "locality", "suburb", "neighbourhood", "quarter", "county", "district", "isolated_dwelling", "island", "area" -> PlaceCategory.Town
  else -> PlaceCategory.Sight
}

/** C2-10: 「↙ 42 km」 from (lat, lon) to (toLat, toLon). */
fun wayText(lat: Double, lon: Double, toLat: Double, toLon: Double): String {
  val arrow = "↑↗→↘↓↙←↖"[(bearing(lat, lon, toLat, toLon) / 45).roundToInt() % 8]
  return "$arrow " + distanceValue(haversine(TrackPoint(0, lat, lon, null), TrackPoint(0, toLat, toLon, null)))
}

/** C2-07: under a result, its 区县 (else what the source says of it), 「· 离线」 when it came from this phone's 地名索引. */
fun resultLine(p: Place, offline: Boolean): String? =
  listOfNotNull(regionOf(p.detail) ?: p.detail, "离线".takeIf { offline }).joinToString(" · ").ifEmpty { null }

/** C2-11: 「{省} {区县}」 from a 地名索引 detail, for the 地点小抽屉; null without either. */
fun regionLine(detail: String?): String? {
  val province = detail?.split(' ')?.firstOrNull { it.endsWith("省") || it.endsWith("自治区") || it.endsWith("市") || it.endsWith("特别行政区") }
  return listOfNotNull(province, regionOf(detail)).distinct().joinToString(" ").ifEmpty { null }
}

private val coordinateToken = Regex("""\s*(?:([NSEW])|(-?\d+(?:\.\d+)?)\s*(''|["'°′″度分秒])?)\s*[,，;]?""")

/**
 * A typed coordinate as (lat, lon), WGS-84 like everything else: decimal degrees or degrees, minutes and
 * seconds, with N/S/E/W (or 北纬 …) before or after each number, or a bare pair: latitude first unless
 * the first can only be a longitude. Null if [text] isn't one.
 */
fun parseCoordinate(text: String): Pair<Double, Double>? {
  var s = text.trim().uppercase()
  for ((k, v) in listOf("北纬" to "N", "南纬" to "S", "东经" to "E", "西经" to "W")) s = s.replace(k, v)
  // Each part: degrees[, minutes[, seconds]] and its hemisphere letter; closed once a letter follows it.
  class Part(val letter: Char?) {
    val numbers = mutableListOf<Double>()
    var suffix: Char? = null
    val hemisphere get() = letter ?: suffix
  }
  val parts = mutableListOf<Part>()
  var pending: Char? = null
  var pos = 0
  while (pos < s.length) {
    val m = coordinateToken.find(s, pos)?.takeIf { it.range.first == pos && it.value.isNotEmpty() } ?: return null
    pos = m.range.last + 1
    val letter = m.groups[1]
    val cur = parts.lastOrNull()
    if (letter != null) {
      if (pending != null) return null
      // "34N": after its number. "N34", "E 107" after a finished part: before the next one.
      val beforeNumber = s.getOrNull(letter.range.last + 1)?.let { it.isDigit() || it == '-' } == true
      if (cur != null && cur.hemisphere == null && !beforeNumber) cur.suffix = letter.value[0] else pending = letter.value[0]
      continue
    }
    val value = m.groupValues[2].toDouble()
    val level = when (m.groupValues[3]) { "'", "′", "分" -> 1; "\"", "″", "''", "秒" -> 2; else -> 0 }
    if (cur != null && cur.suffix == null && pending == null && level > 0 && level == cur.numbers.size) cur.numbers += value
    else if (level == 0) parts += Part(pending).also { it.numbers += value; pending = null }
    else return null
  }
  if (pending != null || parts.size != 2) return null
  val values = parts.map { p ->
    val (deg, min, sec) = listOf(p.numbers[0], p.numbers.getOrElse(1) { 0.0 }, p.numbers.getOrElse(2) { 0.0 })
    if (min !in 0.0..<60.0 || sec !in 0.0..<60.0 || p.numbers.size > 1 && deg != Math.floor(deg)) return null
    val abs = Math.abs(deg) + min / 60 + sec / 3600
    if (p.hemisphere == 'S' || p.hemisphere == 'W' || deg < 0) -abs else abs
  }
  val isLat = parts.map { p -> p.hemisphere?.let { it == 'N' || it == 'S' } }
  val latFirst = when {
    isLat[0] != null && isLat[1] != null -> if (isLat[0] == isLat[1]) return null else isLat[0]!!
    isLat[0] != null -> isLat[0]!!
    isLat[1] != null -> !isLat[1]!!
    else -> Math.abs(values[0]) <= 90
  }
  val (lat, lon) = if (latFirst) values[0] to values[1] else values[1] to values[0]
  return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}

/** Places whose name (or 中文名, English name) contains [query], from each 地名索引 in [files] that exists. */
// ponytail: LIKE scans the whole table (issue #18: ~45 ms for all of China on a desktop); FTS5 trigram if phones lag.
fun searchPlaces(files: List<File>, query: String): List<Place> = files.filter { it.isFile }.flatMap { f ->
  runCatching {
    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
      val sql = """SELECT name, name_zh, name_en, kind, lat, lon, ele, importance, detail FROM places
        WHERE name LIKE ?1 ESCAPE '\' OR name_zh LIKE ?1 ESCAPE '\' OR name_en LIKE ?1 ESCAPE '\'
        ORDER BY CASE WHEN name = ?2 OR name_zh = ?2 OR name_en = ?2 THEN 0 WHEN name LIKE ?3 ESCAPE '\' OR name_zh LIKE ?3 ESCAPE '\' THEN 1 ELSE 2 END,
          importance DESC LIMIT 200"""
      // The same order as rankPlaces up to importance, so the limit drops only the least likely.
      db.rawQuery(sql, arrayOf(like, query, like.drop(1))).use { c ->
        buildList {
          while (c.moveToNext()) {
            val names = listOfNotNull(c.getString(0), c.getString(1), c.getString(2))
            val ele = if (c.isNull(6)) null else "${c.getDouble(6).roundToInt()} m"
            val detail = listOfNotNull(ele, c.getString(8)).joinToString(" · ").ifEmpty { null }
            add(Place(names[0], c.getString(3), c.getDouble(4), c.getDouble(5), detail, c.getDouble(7), names))
          }
        }
      }
    }
  }.getOrDefault(emptyList())
}

/** Places within about 0.05° (5 km) of ([lat], [lon]) in each 地名索引 in [files], for [nearestPlace]: offline. */
// ponytail: a range scan without an index, fine for a package's index; an R-tree if the whole of China lags.
fun placesNear(files: List<File>, lat: Double, lon: Double): List<Place> = files.filter { it.isFile }.flatMap { f ->
  runCatching {
    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      val d = 0.05 / Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1)
      db.rawQuery(
        "SELECT name, kind, lat, lon, detail FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        arrayOf((lat - 0.05).toString(), (lat + 0.05).toString(), (lon - d).toString(), (lon + d).toString()),
      ).use { c -> buildList { while (c.moveToNext()) add(Place(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getString(4))) } }
    }
  }.getOrDefault(emptyList())
}

/** The 地名索引 on this phone: the bundled one and each offline package's. */
fun Context.placeFiles(): List<File> = getExternalFilesDir(null)!!.let { dir ->
  listOf(File(dir, "places.sqlite")) + File(dir, "packages").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.map { File(it, "places.sqlite") }
}

/**
 * A place's 区县 from its 地名索引 detail (「河北省 张家口市 崇礼区」), else its 市 (地区, 州, 盟); null with only a 省.
 * 自治区 and 地区 end in 区 but aren't 区县.
 */
fun regionOf(detail: String?): String? {
  val parts = detail?.split(' ').orEmpty()
  return parts.lastOrNull { it.endsWith("县") || it.endsWith("旗") || it.endsWith("区") && !it.endsWith("自治区") && !it.endsWith("行政区") && !it.endsWith("地区") }
    ?: parts.lastOrNull { it.endsWith("市") || it.endsWith("州") || it.endsWith("盟") || it.endsWith("地区") }
}

/** C3-29 (#154): a recording's name, 「崇礼区 10月5日」 from its start, or just 「10月5日」 without a [region]. */
fun recordingName(region: String?, startMs: Long, nowMs: Long): String = listOfNotNull(region, dayText(startMs, nowMs)).joinToString(" ")

/** A recording's name (§8.3 第 15 条) from its [start]: after the 区县 of the nearest place, offline. Reads files. */
fun Context.recordingNameFrom(start: TrackPoint): String {
  val place = nearestPlace(placesNear(placeFiles(), start.lat, start.lon).filter { regionOf(it.detail) != null }, start.lat, start.lon)
  return recordingName(regionOf(place?.detail), start.timeMs, System.currentTimeMillis())
}
