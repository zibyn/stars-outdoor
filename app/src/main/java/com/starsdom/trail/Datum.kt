package com.starsdom.trail

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 坐标纠偏 (§2.6): the datum a track's raw coordinates were recorded in. Stored points stay raw; this is applied on read. */
enum class Datum(val label: String) {
  WGS84("其他"),
  GCJ02("高德·腾讯"),
  BD09("百度");

  /** Only coordinates inside China were ever shifted, so only those are corrected. */
  fun toWgs84(lat: Double, lon: Double): Pair<Double, Double> = when {
    this == WGS84 || outOfChina(lat, lon) -> lat to lon
    this == GCJ02 -> gcj02ToWgs84(lat, lon)
    else -> bd09ToGcj02(lat, lon).let { (g, h) -> gcj02ToWgs84(g, h) }
  }
}

// ponytail: bounding box, as every GCJ-02 implementation uses; a China polygon if border tracks shift wrongly.
fun outOfChina(lat: Double, lon: Double) = lon !in 72.004..137.8347 || lat !in 0.8293..55.8271

private const val A = 6378245.0
private const val EE = 0.00669342162296594323
private const val X_PI = PI * 3000.0 / 180.0

fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
  val x = lon - 105.0
  val y = lat - 35.0
  var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x)) +
    (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
    (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0 +
    (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
  var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x)) +
    (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0 +
    (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0 +
    (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
  val radLat = lat / 180.0 * PI
  val magic = 1 - EE * sin(radLat) * sin(radLat)
  dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrt(magic)) * PI)
  dLon = dLon * 180.0 / (A / sqrt(magic) * cos(radLat) * PI)
  return lat + dLat to lon + dLon
}

/** GCJ-02 has no closed-form inverse; a few fixed-point steps converge well below a millimetre. */
private fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
  var wLat = lat
  var wLon = lon
  repeat(5) {
    val (gLat, gLon) = wgs84ToGcj02(wLat, wLon)
    wLat += lat - gLat
    wLon += lon - gLon
  }
  return wLat to wLon
}

fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
  val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
  val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
  return z * sin(theta) + 0.006 to z * cos(theta) + 0.0065
}

private fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
  val x = lon - 0.0065
  val y = lat - 0.006
  val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
  val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
  return z * sin(theta) to z * cos(theta)
}
