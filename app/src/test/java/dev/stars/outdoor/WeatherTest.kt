package dev.stars.outdoor

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
  private val zone = TimeZone.getTimeZone("Asia/Shanghai")
  // 2026-09-28 08:00 Beijing time.
  private val depart = 1_790_553_600_000L
  private val hour = 3_600_000L

  private fun assertNear(expected: Long, actual: Long, tolerance: Long) =
    assertTrue("$actual is not within $tolerance of $expected", kotlin.math.abs(actual - expected) <= tolerance)

  // Due north from 33.96°N, 107.77°E; 0.001° of latitude ≈ 111.2 m.
  private fun line(km: Double, ele: (Double) -> Double? = { 1000.0 }) =
    (0..(km * 10).toInt()).map { i -> TrackPoint(0, 33.96 + i * 0.1 / 111.2, 107.77, ele(i * 0.1)) }

  @Test
  fun toblerIsAbout5KmhOnTheFlatAndSlowerUphill() {
    assertEquals(5.04, toblerKmh(0.0), 0.01)
    assertTrue(toblerKmh(0.2) < 3.0)
    assertTrue(toblerKmh(-0.05) > toblerKmh(0.0))
  }

  @Test
  fun arrivalFollowsToblerTimesThePace() {
    val flat = line(5.0)
    val medium = samples(flat, depart, Pace.Medium).last()
    assertEquals(5000.0, medium.distM, 5.0)
    // 5 km at 5.04 km/h ≈ 59.5 min.
    assertNear(depart + (5 / 5.04 * hour).toLong(), medium.etaMs, 60_000)
    assertTrue(samples(flat, depart, Pace.Slow).last().etaMs > medium.etaMs)
    assertTrue(samples(flat, depart, Pace.Fast).last().etaMs < medium.etaMs)
  }

  @Test
  fun samplesEvery5KmOrHourWhicheverFirstPlusStartEndAndTop() {
    // Flat: 5 km comes before the hour (5.04 km).
    val flat = samples(line(12.0), depart, Pace.Medium)
    assertEquals(listOf(0L, 5L, 10L, 12L), flat.map { Math.round(it.distM / 1000) })
    // Climbing 20%: about 2.6 km an hour, so hourly; the top (at the end) is marked.
    val climb = samples(line(6.0) { d -> 1000 + d * 200 }, depart, Pace.Medium)
    assertTrue(climb.size in 4..5)
    assertTrue(climb.zipWithNext().all { (a, b) -> b.etaMs - a.etaMs <= hour + 5 * 60_000 && b.distM - a.distM <= 5100.0 })
    assertTrue(climb.last().highest)
    // A peak mid-way is sampled even when neither rule would land on it.
    val peak = samples(line(3.0) { d -> 1000 + 300 - kotlin.math.abs(d - 1.23) * 100 }, depart, Pace.Medium)
    assertEquals(1.2, peak.single { it.highest }.distM / 1000, 0.05)
  }

  private val start = Sample(TrackPoint(0, 33.96, 107.77, 1000.0), depart, 0.0, false)
  private val top = Sample(TrackPoint(0, 33.97, 107.77, 1500.0), depart + 2 * hour, 3200.0, true)
  private val end = Sample(TrackPoint(0, 33.98, 107.77, 1000.0), depart + 4 * hour, 6400.0, false)
  private val calm = WeatherHour(temp = 12.0, feelsLike = 10.0, precip = 0.0, gust = 5.0, thunder = false, elevation = 1000.0)

  private fun alerts(vararg hours: WeatherHour, official: List<OfficialAlert> = emptyList(), last: Sample = end) =
    tripAlerts(listOf(start, top, last), Forecast(hours.withIndex().associate { (i, h) -> i to h }, official, listOf("qweather")), zone)

  @Test
  fun calmDayHasNoAlerts() = assertEquals(emptyList<TripAlert>(), alerts(calm, calm, calm))

  @Test
  fun thunderFromTheWeatherOrAWarning() {
    assertEquals("雷暴：约 10:00 在 3.2 km 处有雷阵雨", alerts(calm, calm.copy(thunder = true), calm).single().text)
    val warning = OfficialAlert("a1", "周至县发布雷电黄色预警", "预计未来6小时有雷电活动", thunder = true)
    assertEquals(listOf(Risk.Thunder, Risk.Official), alerts(calm, calm, calm, official = listOf(warning)).map { it.risk })
  }

  @Test
  fun heavyRainFrom8mmAnHour() {
    assertEquals(emptyList<TripAlert>(), alerts(calm, calm.copy(precip = 7.9), calm))
    assertEquals("强降水：约 10:00 在 3.2 km 处小时降水 8.0 mm", alerts(calm, calm.copy(precip = 8.0), calm).single().text)
  }

  @Test
  fun galesFrom17point2AndAgainAtTheTop() {
    assertEquals(emptyList<TripAlert>(), alerts(calm.copy(gust = 17.1), calm, calm))
    assertEquals(listOf("大风：约 08:00 在 0.0 km 处阵风 17.2 m/s"), alerts(calm.copy(gust = 17.2), calm, calm).map { it.text })
    assertEquals(
      listOf("大风：约 08:00 在 0.0 km 处阵风 20.0 m/s", "大风：最高点（1500 m）约 10:00 阵风 18.0 m/s"),
      alerts(calm.copy(gust = 20.0), calm.copy(gust = 18.0), calm).map { it.text },
    )
    // First met at the top: said once.
    assertEquals(listOf("大风：最高点（1500 m）约 10:00 阵风 18.0 m/s"), alerts(calm, calm.copy(gust = 18.0), calm).map { it.text })
  }

  @Test
  fun coldFeelsLikeAtOrBelowZeroAfterTheElevationCorrection() {
    assertEquals(emptyList<TripAlert>(), alerts(calm.copy(feelsLike = 0.1), calm, calm))
    assertEquals("低温：约 08:00 在 0.0 km 处体感 0°C", alerts(calm.copy(feelsLike = 0.0), calm, calm).single().text)
    // The top is 500 m above the cell's ground: 3.0 − 3.25 °C.
    assertEquals(Risk.Cold, alerts(calm, calm.copy(feelsLike = 3.0), calm).single().risk)
    assertEquals(emptyList<TripAlert>(), alerts(calm, calm.copy(feelsLike = 3.0, elevation = null), calm))
  }

  @Test
  fun darkWhenTheEndIsReachedLaterThanHalfAnHourBeforeSunset() {
    // Sunset there on 28 Sep ≈ 18:39.
    val sunset = sunsetMs(33.98, 107.77, end.etaMs, zone)!!
    assertNear(depart + 10 * hour + 39 * 60_000, sunset, 3 * 60_000)
    assertEquals(emptyList<TripAlert>(), alerts(calm, calm, calm, last = end.copy(etaMs = sunset - 31 * 60_000)))
    val late = alerts(calm, calm, calm, last = end.copy(etaMs = sunset - 29 * 60_000)).single()
    assertEquals(Risk.Dark, late.risk)
    assertEquals("天黑前走不完：预计 18:09 到达终点，日落 18:38", late.text)
  }

  @Test
  fun arrivingAfterMidnightIsStillAfterDark() {
    val night = alerts(calm, calm, calm, last = end.copy(etaMs = depart + 17 * hour)).single() // 01:00 the next day
    assertEquals("天黑前走不完：预计 01:00 到达终点，日落 18:38", night.text)
  }

  @Test
  fun aLongTrackKeepsStartTopAndEndWithinTheLimit() {
    // 600 km at 5 km a sample: 121 samples, top in the middle.
    val s = samples(line(600.0) { d -> if (d in 299.9..300.1) 2000.0 else 1000.0 }, depart, Pace.Medium)
    assertEquals(MAX_WEATHER_POINTS, s.size)
    assertEquals(0.0, s.first().distM, 0.0)
    assertEquals(600.0, s.last().distM / 1000, 0.1)
    assertTrue(s.any { it.highest })
  }

  @Test
  fun departureIsTheOneSetWhileAheadElseNow() {
    val w = TrackWeather(depart - hour, depart, emptyList(), "{}", offline = false)
    assertEquals(depart, departure(w, depart - 1))
    assertEquals(depart + 1, departure(w, depart + 1))
    assertEquals(depart, departure(null, depart))
  }

  @Test
  fun officialWarningsAreForwardedAsIs() {
    val a = OfficialAlert("a2", "宁陕县发布大风蓝色预警", "阵风可达8级", thunder = false)
    assertEquals(listOf(TripAlert(Risk.Official, "宁陕县发布大风蓝色预警", "阵风可达8级")), alerts(calm, calm, calm, official = listOf(a)))
  }

  @Test
  fun sunsetInBeijingAtTheSolstice() {
    val noon = 1_782_014_400_000L // 2026-06-21 12:00 Beijing
    assertNear(noon + 7 * hour + 46 * 60_000, sunsetMs(39.9, 116.4, noon, zone)!!, 60_000)
    assertEquals(null, sunsetMs(80.0, 0.0, noon, zone)) // polar day
  }

  @Test
  fun offlineForecastSaysHowOldAndGoesStaleAfter12Hours() {
    val fetched = depart
    assertEquals("预报更新于 3 小时前", updatedText(fetched, fetched + 3 * hour + 59 * 60_000))
    assertEquals("预报更新于 20 分钟前", updatedText(fetched, fetched + 20 * 60_000))
    assertFalse(stale(fetched, fetched + 12 * hour))
    assertTrue(stale(fetched, fetched + 12 * hour + 1))
  }

  @Test
  fun forecastAndCacheRoundTrip() {
    val json = """{"hours":[{"point":1,"temp":1,"feelsLike":-6.8,"precip":9.5,"gust":20.8,"thunder":true,"elevation":1520}],
      "warnings":[{"id":"a1","title":"t","text":"x","thunder":true}],"sources":["qweather"]}"""
    val f = parseForecast(json)
    assertEquals(WeatherHour(1.0, -6.8, 9.5, 20.8, true, 1520.0), f.hours[1])
    assertEquals(listOf(OfficialAlert("a1", "t", "x", true)), f.alerts)
    val w = TrackWeather(depart, depart + hour, listOf(start, top, end), json, offline = false)
    assertEquals(w, readWeather(writeWeather(w)))
    val req = weatherRequest(listOf(start, top))
    assertEquals("""{"points":[{"lon":107.77,"lat":33.96,"time":1790553600},{"lon":107.77,"lat":33.97,"time":1790560800}]}""", req)
  }
}
