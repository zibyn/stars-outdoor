package dev.stars.outdoor

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

  @Test
  fun exactBeforePrefixBeforeContainsThenImportanceThenDistance() {
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
    assertEquals(listOf(offline), rankPlaces(listOf(offline, online), "拔仙台", qinling.first, qinling.second))
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
