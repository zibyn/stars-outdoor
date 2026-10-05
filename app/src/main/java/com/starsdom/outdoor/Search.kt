package com.starsdom.outdoor

// 搜索 (spec §2.10): coordinates typed in, parsed here; places from the 山名别名表, the offline packages'
// 地名索引 and, online, the server (Photon, 天地图), ranked together.

import android.database.sqlite.SQLiteDatabase
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
 * Exact name matches first, then names starting with [query], then the rest; within each, more important
 * places (to 0.1), then nearer to ([lat], [lon]). The same place from two sources (same name and kind
 * within 200 m) shows once.
 */
// ponytail: importance in 0.1 steps, then distance; a blended score if far famous places keep beating near ones.
fun rankPlaces(places: List<Place>, query: String, lat: Double, lon: Double): List<Place> {
  val q = query.trim().lowercase()
  fun level(p: Place) = p.names.map { it.lowercase() }.let { n ->
    when {
      q in n -> 0
      n.any { it.startsWith(q) } -> 1
      n.any { q in it } -> 2
      else -> 3
    }
  }
  val here = TrackPoint(0, lat, lon, null)
  val ranked = places.sortedWith(compareBy<Place>({ level(it) }, { -Math.floor(it.importance * 10) }, { haversine(here, it.point) }))
  val kept = mutableListOf<Place>()
  for (p in ranked) if (kept.none { it.name == p.name && it.kind == p.kind && haversine(it.point, p.point) < 200 }) kept += p
  return kept
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
        "SELECT name, kind, lat, lon FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
        arrayOf((lat - 0.05).toString(), (lat + 0.05).toString(), (lon - d).toString(), (lon + d).toString()),
      ).use { c -> buildList { while (c.moveToNext()) add(Place(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3))) } }
    }
  }.getOrDefault(emptyList())
}
