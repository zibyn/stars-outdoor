package com.starsdom.outdoor

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
  private val zone = TimeZone.getTimeZone("Asia/Shanghai")
  // 2026-09-28 08:00 Beijing time.
  private val start = 1_790_553_600_000L
  private val hour = 3_600_000L

  private fun assertNear(expected: Long, actual: Long, tolerance: Long) =
    assertTrue("$actual is not within $tolerance of $expected", kotlin.math.abs(actual - expected) <= tolerance)

  private val calm = WeatherHour(temp = 12.0, feelsLike = 10.0, precip = 0.0, gust = 5.0, thunder = false, elevation = 1000.0)

  /** Hour i of [hours] from 08:00 at 33.96°N 107.77°E, standing at [ele]. */
  private fun place(vararg hours: WeatherHour, official: List<OfficialAlert> = emptyList(), ele: Double? = 1000.0): PlaceWeather {
    val w = PlaceWeather(33.96, 107.77, ele, start, start, "{}")
    val f = Forecast(hours.withIndex().associate { (i, h) -> i to h }, official, listOf("qweather"))
    return w.copy(response = forecastJson(f))
  }

  private fun forecastJson(f: Forecast) = """{"hours":[${f.hours.entries.joinToString(",") { (i, h) ->
    """{"point":$i,"temp":${h.temp},"feelsLike":${h.feelsLike},"precip":${h.precip},"gust":${h.gust},"thunder":${h.thunder},"elevation":${h.elevation}${h.sky?.let { ""","sky":"${it.name.lowercase()}"""" } ?: ""}}"""
  }}],"warnings":[${f.alerts.joinToString(",") { """{"id":"${it.id}","title":"${it.title}","text":"${it.text}","thunder":${it.thunder}}""" }}],"sources":["qweather"]}"""

  private fun alerts(w: PlaceWeather, hours: Int = 3) = alerts(w, start, start + hours * hour, zone)

  @Test
  fun calmHoursHaveNoAlerts() = assertEquals(emptyList<TripAlert>(), alerts(place(calm, calm, calm)))

  @Test
  fun thunderFromTheWeatherOrAWarning() {
    assertEquals("雷暴：约 09:00 起有雷阵雨", alerts(place(calm, calm.copy(thunder = true), calm)).single().text)
    val warning = OfficialAlert("a1", "周至县发布雷电黄色预警", "预计未来6小时有雷电活动", thunder = true)
    assertEquals(listOf(Risk.Thunder, Risk.Official), alerts(place(calm, calm, calm, official = listOf(warning))).map { it.risk })
  }

  @Test
  fun eachRiskAtItsFirstHourPastTheThreshold() {
    assertEquals(emptyList<TripAlert>(), alerts(place(calm.copy(precip = 7.9), calm.copy(gust = 17.1), calm.copy(feelsLike = 0.1))))
    assertEquals(
      listOf("强降水：约 09:00 小时降水 8.0 mm", "大风：约 08:00 阵风 17.2 m/s", "低温：约 10:00 体感 0°C"),
      alerts(place(calm.copy(gust = 17.2), calm.copy(precip = 8.0), calm.copy(feelsLike = 0.0, gust = 20.0))).map { it.text },
    )
  }

  @Test
  fun onlyTheHoursAskedFor() {
    val w = place(calm, calm, calm, calm.copy(thunder = true))
    assertEquals(emptyList<TripAlert>(), alerts(w, hours = 3))
    assertEquals(Risk.Thunder, alerts(w, hours = 4).single().risk)
    // Half past nine: 08:00 is over, 09:00 is still on.
    assertEquals(emptyList<TripAlert>(), alerts(place(calm.copy(thunder = true), calm), start + hour + hour / 2, start + 2 * hour, zone))
  }

  @Test
  fun coldAfterTheElevationCorrection() {
    // Standing 500 m above the cell's ground: 3.0 − 3.25 °C.
    assertEquals(Risk.Cold, alerts(place(calm.copy(feelsLike = 3.0), ele = 1500.0)).single().risk)
    assertEquals(emptyList<TripAlert>(), alerts(place(calm.copy(feelsLike = 3.0), ele = null)))
  }

  @Test
  fun hoursRunOnFromTheStartAndAtFindsTheOneNow() {
    val w = place(calm, calm.copy(temp = 13.0))
    assertEquals(listOf(start, start + hour), w.hours().map { it.first })
    assertEquals(13.0, w.at(start + hour + 59 * 60_000)!!.temp, 0.0)
    assertNull(w.at(start - 1))
    assertNull(w.at(start + 2 * hour))
  }

  @Test
  fun officialWarningsAreForwardedAsIs() {
    val a = OfficialAlert("a2", "宁陕县发布大风蓝色预警", "阵风可达8级", thunder = false)
    assertEquals(listOf(TripAlert(Risk.Official, "宁陕县发布大风蓝色预警", "阵风可达8级")), alerts(place(calm, official = listOf(a))))
  }

  @Test
  fun sunriseAndSunsetInBeijingAtTheSolstice() {
    val noon = 1_782_014_400_000L // 2026-06-21 12:00 Beijing
    assertNear(noon + 7 * hour + 46 * 60_000, sunsetMs(39.9, 116.4, noon, zone)!!, 60_000)
    assertNear(noon - 7 * hour - 14 * 60_000, sunriseMs(39.9, 116.4, noon, zone)!!, 60_000)
    assertEquals(null, sunsetMs(80.0, 0.0, noon, zone)) // polar day
  }

  @Test
  fun offlineForecastSaysHowOldAndGoesStaleAfter12Hours() {
    assertEquals("预报更新于 3 小时前", updatedText(start, start + 3 * hour + 59 * 60_000))
    assertEquals("预报更新于 20 分钟前", updatedText(start, start + 20 * 60_000))
    assertFalse(stale(start, start + 12 * hour))
    assertTrue(stale(start, start + 12 * hour + 1))
  }

  @Test
  fun requestForecastAndCacheRoundTrip() {
    assertEquals(
      """{"points":[{"lon":107.77,"lat":33.96,"time":1790553600},{"lon":107.77,"lat":33.96,"time":1790557200}]}""",
      weatherRequest(33.96, 107.77, start, 2),
    )
    val json = """{"hours":[{"point":1,"temp":1,"feelsLike":-6.8,"precip":9.5,"gust":20.8,"thunder":true,"elevation":1520},
      {"point":2,"temp":1,"feelsLike":-6.8,"precip":9.5,"gust":20.8,"thunder":true,"sky":"rain","windDir":225}],
      "warnings":[{"id":"a1","title":"t","text":"x","thunder":true}],"sources":["qweather"]}"""
    val f = parseForecast(json)
    assertEquals(WeatherHour(1.0, -6.8, 9.5, 20.8, true, 1520.0), f.hours[1])
    assertEquals(WeatherHour(1.0, -6.8, 9.5, 20.8, true, null, Sky.Rain, 225.0), f.hours[2])
    assertEquals(listOf(OfficialAlert("a1", "t", "x", true)), f.alerts)
    val w = PlaceWeather(33.96, 107.77, null, start + 60_000, start, json)
    assertEquals(w, readWeather(writeWeather(w)))
  }

  @Test
  fun daysSplitAtMidnightWithHighsLowsAndIcon() {
    // From 08:00: the rest of today clear by day, cloudy after 20:00, 20° at 14:00; tomorrow 1.5 mm of rain and a
    // gale at noon; 8 hours of the day after.
    val hours = (0 until 48).map { i ->
      when {
        i == 6 -> calm.copy(temp = 20.0, sky = Sky.Clear)
        i < 12 -> calm.copy(sky = Sky.Clear)
        i < 16 -> calm.copy(sky = Sky.Cloudy)
        i in 20..22 -> calm.copy(precip = 0.5, sky = Sky.Rain)
        i == 28 -> calm.copy(gust = 20.0, sky = Sky.Cloudy)
        else -> calm.copy(sky = Sky.Cloudy)
      }
    }
    val days = weatherDays(place(*hours.toTypedArray()), start + 30 * 60_000, zone)
    assertEquals(listOf(16, 24, 8), days.map { it.hours.size })
    assertEquals(start + 16 * hour, days[1].startMs)
    val (today, tomorrow) = days
    assertEquals(listOf(20, 12, Sky.Clear, false), listOf(today.high, today.low, today.sky, today.risky))
    assertEquals(listOf(Sky.Rain, true, true), listOf(tomorrow.sky, tomorrow.risky, tomorrow.stormy))
    // Freezing alone is risky but not stormy.
    val cold = weatherDays(place(calm.copy(feelsLike = -2.0)), start, zone).single()
    assertEquals(listOf(true, false), listOf(cold.risky, cold.stormy))
    assertEquals(1.5, tomorrow.precip, 1e-9)
    // Later in the day, today starts from the current hour.
    assertEquals(4, weatherDays(place(*hours.toTypedArray()), start + 12 * hour, zone).first().hours.size)
  }

  /** 20 km due north in 100 m steps, highest 7.3 km in. */
  private val line = listOf((0..200).map { i -> TrackPoint(0, 30 + i * 0.1 / 111.195, 103.0, 2000 - kotlin.math.abs(i - 73) * 5.0) })

  @Test
  fun spotsAlongATrack() {
    val spots = trackSpots(line)
    assertEquals(listOf("起点", "最高点", "10 km", "15 km", "终点"), spots.map { it.label })
    assertEquals(7300.0, spots[1].distM, 1.0)
    assertEquals("海拔 2000 m，沿轨 7.3 km", spotText(spots[1]))
  }

  @Test
  fun aLongTrackKeepsAtMostEightSpots() = assertTrue(trackSpots(line, everyM = 500.0).size <= 8)

  @Test
  fun aLoopEndsWhereItStarts() {
    val loop = listOf(line[0] + line[0].reversed())
    assertEquals(listOf("起终点", "最高点"), trackSpots(loop).map { it.label }.filter { "km" !in it })
  }
}
