package com.starsdom.outdoor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
  private fun assertCoordinate(lat: Double, lon: Double, text: String) {
    val c = parseCoordinate(text) ?: throw AssertionError("$text: not a coordinate")
    assertEquals(text, lat, c.first, 1e-6)
    assertEquals(text, lon, c.second, 1e-6)
  }

  @Test
  fun decimalDegreesWithHemispheres() {
    assertCoordinate(34.0, 107.8, "34.0N 107.8E")
    assertCoordinate(34.0, 107.8, "107.8E 34.0N")
    assertCoordinate(34.0, 107.8, "N34.0 E107.8")
    assertCoordinate(-33.5, -70.25, "33.5S, 70.25W")
    assertCoordinate(34.0, 107.8, "北纬34.0 东经107.8")
  }

  @Test
  fun barePairIsLatThenLonUnlessTheFirstCanOnlyBeALongitude() {
    assertCoordinate(34.0, 107.8, "34.0, 107.8")
    assertCoordinate(34.0, 107.8, "34.0 107.8")
    assertCoordinate(34.0, 107.8, "107.8，34.0")
    assertCoordinate(-33.5, 151.2, "-33.5,151.2")
  }

  @Test
  fun degreesMinutesSeconds() {
    assertCoordinate(30 + 7 / 60.0 + 22 / 3600.0, 103 + 27 / 60.0 + 21 / 3600.0, "N30°07'22\" E103°27'21\"")
    assertCoordinate(30 + 7.37 / 60, 103 + 27.35 / 60, "30°07.37'N 103°27.35'E")
    assertCoordinate(34.5, 107.75, "34度30分 107度45分")
    assertCoordinate(-(30 + 7 / 60.0 + 22 / 3600.0), 103.0, "30° 07′ 22″ S 103° E")
  }

  @Test
  fun notACoordinate() {
    for (text in listOf("太白山", "34.0", "34 107 12", "95 200", "34N 12N", "34°61' 107°", "N", "", "G108 34.0 107.8")) {
      assertNull(text, parseCoordinate(text))
    }
  }

  private val qinling = 34.0 to 107.8

  // §8.2 第 1 条: 汉字全匹配 → 汉字前缀 → the rest (拼音, inside the name), each by distance alone.
  @Test
  fun sameLevelGoesByDistanceNotImportance() {
    val famous = Place("太白山", "peak", 30.0, 120.0, importance = 0.9)
    val near = Place("太白山", "village", 34.1, 107.9)
    val pinyin = Place("太白山", "peak", 34.0, 107.8)
    assertEquals(listOf(near, famous), rankPlaces(listOf(famous, near), "太白山", qinling.first, qinling.second))
    // A server answer to 「taibai」 matched it by 拼音: after the names that match as typed.
    val typed = Place("Taibai Lodge", "alpine_hut", 20.0, 100.0)
    assertEquals(listOf(typed, pinyin), rankPlaces(listOf(pinyin, typed), "taibai", qinling.first, qinling.second))
  }

  @Test
  fun categoryByKind() {
    assertEquals(PlaceCategory.Peak, placeCategory("peak"))
    assertEquals(PlaceCategory.Peak, placeCategory("saddle"))
    assertEquals(PlaceCategory.Water, placeCategory("waterfall"))
    assertEquals(PlaceCategory.Water, placeCategory("spring"))
    assertEquals(PlaceCategory.Town, placeCategory("village"))
    assertEquals(PlaceCategory.Town, placeCategory("area"))
    assertEquals(PlaceCategory.Sight, placeCategory("viewpoint"))
    assertEquals(PlaceCategory.Sight, placeCategory("coordinate"))
  }

  // C2-10: 「↙ 42 km」, the way to go from where the distance is counted.
  @Test
  fun wayText() {
    assertEquals("↑ 11 km", wayText(34.0, 107.8, 34.1, 107.8))
    assertEquals("↙ 855 m", wayText(34.0, 107.8, 34.0 - 0.0054, 107.8 - 0.0066))
    assertEquals("→ 3.2 km", wayText(34.0, 107.8, 34.0, 107.8 + 0.0347))
  }

  // C2-07, C2-11: 「{区县}」 under a result, 「{省} {区县}」 in the 地点小抽屉.
  @Test
  fun placeLines() {
    assertEquals("崇礼区 · 离线", resultLine(Place("太舞", "village", 40.9, 115.4, "河北省 张家口市 崇礼区"), offline = true))
    assertEquals("主峰 拔仙台", resultLine(Place("太白山", "peak", 34.0, 107.8, "主峰 拔仙台"), offline = false))
    assertEquals("眉县", resultLine(Place("x", "peak", 34.0, 107.8, "2100 m · 陕西省 宝鸡市 眉县"), offline = false))
    assertEquals(null, resultLine(Place("x", "peak", 34.0, 107.8), offline = false))
    assertEquals("离线", resultLine(Place("x", "peak", 34.0, 107.8), offline = true))
    assertEquals("河北省 崇礼区", regionLine("河北省 张家口市 崇礼区"))
    assertEquals("北京市 延庆区", regionLine("北京市 延庆区"))
    assertEquals("陕西省 眉县", regionLine("2100 m · 陕西省 宝鸡市 眉县"))
    assertEquals(null, regionLine("主峰 拔仙台"))
  }

  @Test
  fun exactBeforePrefixBeforeContainsThenDistance() {
    val japan = Place("太白山", "peak", 38.2, 140.5, importance = 0.25)
    val village = Place("太白山村", "village", 34.1, 107.7)
    val road = Place("东太白山路", "locality", 34.0, 107.8)
    val near = Place("太白山", "peak", 34.2, 107.9, importance = 0.21)
    val far = Place("太白山", "peak", 30.0, 120.0, importance = 0.22)
    assertEquals(listOf(near, far, japan, village, road), rankPlaces(listOf(road, village, japan, far, near), "太白山", qinling.first, qinling.second))
  }

  @Test
  fun otherNamesMatchToo() {
    val k2 = Place("乔戈里峰 کے ٹو", "peak", 35.88, 76.51, names = listOf("乔戈里峰 کے ٹو", "乔戈里峰", "K2"))
    assertEquals(listOf(k2), rankPlaces(listOf(k2), "k2", qinling.first, qinling.second))
  }

  @Test
  fun samePlaceFromTwoSourcesShowsOnce() {
    val offline = Place("拔仙台", "peak", 33.95512, 107.76528, importance = 0.3)
    val online = Place("拔仙台", "peak", 33.9552, 107.7653, detail = "陕西省 宝鸡市")
    val temple = Place("拔仙台", "place_of_worship", 33.95502, 107.76496)
    assertEquals(1, rankPlaces(listOf(offline, online), "拔仙台", qinling.first, qinling.second).size)
    assertEquals(2, rankPlaces(listOf(offline, temple), "拔仙台", qinling.first, qinling.second).size)
  }

  @Test
  fun mountainNameFindsItsSummitThroughTheAliasTable() {
    val aliases = aliasPlaces(File("src/main/assets/peak-aliases.tsv").readText())
    val japan = Place("太白山", "peak", 38.2, 140.5, importance = 0.25)
    val top = rankPlaces(aliases + japan, "太白山", 39.9, 116.4).first()
    assertEquals("太白山", top.name)
    assertEquals("主峰 拔仙台", top.detail)
    assertEquals(33.955, top.lat, 0.01)
    assertEquals(107.765, top.lon, 0.01)
  }
}

class RecordingNameTest {
  @Test
  fun regionIsTheCountyElseTheCity() {
    assertEquals("崇礼区", regionOf("河北省 张家口市 崇礼区"))
    assertEquals("延庆区", regionOf("北京市 延庆区"))
    assertEquals("布尔津县", regionOf("新疆维吾尔自治区 阿勒泰地区 布尔津县"))
    assertEquals("阿勒泰地区", regionOf("新疆维吾尔自治区 阿勒泰地区"))
    assertEquals("张家口市", regionOf("河北省 张家口市"))
    assertEquals("北京市", regionOf("北京市"))
    assertNull(regionOf("河北省"))
    assertNull(regionOf(null))
  }

  @Test
  fun nameIsRegionAndDayOrJustTheDay() {
    val oct5 = java.util.GregorianCalendar(2026, 9, 5, 8, 30).timeInMillis
    assertEquals("崇礼区 10月5日", recordingName("崇礼区", oct5, oct5))
    assertEquals("10月5日", recordingName(null, oct5, oct5))
  }
}
