package com.starsdom.trail.track

import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal actual fun unzip(bytes: ByteArray, limit: Long): List<Pair<String, ByteArray>> {
  val files = mutableListOf<Pair<String, ByteArray>>()
  var total = 0L
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    while (true) {
      val entry = zip.nextEntry ?: break
      if (entry.isDirectory) continue
      val data = zip.readAtMost(limit - total + 1)
      total += data.size
      check(total <= limit) { "zip over ${limit / 1024 / 1024} MB inflated" }
      files += entry.name to data
    }
  }
  return files
}

/** Reads at most [limit] bytes (InputStream.readNBytes is API 33+ on Android). */
fun InputStream.readAtMost(limit: Long): ByteArray {
  val out = ByteArrayOutputStream()
  val buf = ByteArray(64 * 1024)
  while (out.size() < limit) {
    val n = read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
    if (n < 0) break
    out.write(buf, 0, n)
  }
  return out.toByteArray()
}

internal actual fun decode(bytes: ByteArray, charset: String) = String(bytes, charset(charset))

// ponytail: FIT records only, as one segment; split at timer stop/start events if paused activities matter.
internal actual fun parseFit(bytes: ByteArray): TrackFile {
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
