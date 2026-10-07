package com.starsdom.trail.weather

import com.starsdom.trail.team.updatedText
import com.starsdom.trail.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

class WeatherTest {
  private val zone = TimeZone.of("Asia/Shanghai")
  // 2026-09-28 08:00 Beijing time.
  private val start = 1_790_553_600_000L
  private val hour = 3_600_000L

  private fun assertNear(expected: Long, actual: Long, tolerance: Long) =
    assertTrue(kotlin.math.abs(actual - expected) <= tolerance, "$actual is not within $tolerance of $expected")

  private val calm = WeatherHour(temp = 12.0, feelsLike = 10.0, precip = 0.0, gust = 5.0, thunder = false, sky = Sky.Cloudy)

  /** Hour i of [hours] from 08:00 at 33.96°N 107.77°E. */
  private fun place(vararg hours: WeatherHour, official: List<OfficialAlert> = emptyList()) =
    PlaceWeather(33.96, 107.77, 1000.0, start, answer(hours.toList(), official))

  /** GET /v1/weather's answer with [hours] from 08:00, as the server sends it. */
  private fun answer(hours: List<WeatherHour>, official: List<OfficialAlert> = emptyList(), forecast: String = "ok", extra: String = "") =
    """{"forecast":"$forecast",${if (hours.isEmpty()) "" else "\"elevation\":1000,"}"hours":[${hours.withIndex().joinToString(",") { (i, h) ->
      """{"time":${start / 1000 + i * 3600},"temp":${h.temp},"feelsLike":${h.feelsLike},"precip":${h.precip},"gust":${h.gust},"thunder":${h.thunder},"sky":"${h.sky!!.name.lowercase()}"${detail(h)}}"""
    }}],"warnings":[${official.joinToString(",") { """{"id":"${it.id}","title":"${it.title}","text":"${it.text}","thunder":${it.thunder}}""" }}]$extra,"sources":["open-meteo","qweather"]}"""

  /** [h]'s detail fields there are, as the server sends them. */
  private fun detail(h: WeatherHour) = listOf(
    "cloudLow" to h.cloudLow, "cloudMid" to h.cloudMid, "cloudHigh" to h.cloudHigh, "cloudSea" to h.cloudSea?.name?.lowercase()?.let { "\"$it\"" },
    "thunderPotential" to h.thunderPotential?.name?.lowercase()?.let { "\"$it\"" }, "freezingLevel" to h.freezingLevel,
  ).filter { it.second != null }.joinToString("") { (k, v) -> ",\"$k\":$v" }

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
  fun coldByTheServersFeelsLikeAsIs() {
    assertEquals(emptyList<TripAlert>(), alerts(place(calm.copy(feelsLike = 0.1))))
    assertEquals(Risk.Cold, alerts(place(calm.copy(feelsLike = 0.0))).single().risk)
  }

  @Test
  fun hoursGoByTheirTimeAndAtFindsTheOneNow() {
    // Sent out of order: put in order by their own time.
    val json = """{"forecast":"ok","hours":[{"time":1790557200,"temp":13,"feelsLike":10,"precip":0,"gust":5,"thunder":false,"sky":"cloudy"},
      {"time":1790553600,"temp":12,"feelsLike":10,"precip":0,"gust":5,"thunder":false,"sky":"cloudy"}],"warnings":[],"sources":["open-meteo"]}"""
    val w = PlaceWeather(33.96, 107.77, null, start, json)
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
    assertEquals("3 小时前", updatedText(start, start + 3 * hour + 59 * 60_000))
    assertEquals("20 分钟前", updatedText(start, start + 20 * 60_000))
    assertFalse(stale(start, start + 12 * hour))
    assertTrue(stale(start, start + 12 * hour + 1))
  }

  @Test
  fun parseForecastAndCacheRoundTrip() {
    val json = """{"forecast":"ok","elevation":1900,"hours":[{"time":1790553600,"temp":1,"feelsLike":-6.8,"precip":9.5,"gust":20.8,"thunder":true,"sky":"cloudy"},
      {"time":1790557200,"temp":1,"feelsLike":-6.8,"precip":9.5,"gust":20.8,"thunder":true,"sky":"rain","windDir":225}],
      "warnings":[{"id":"a1","title":"t","text":"x","thunder":true,"sender":"萍乡市气象台","issuedAt":"2026-10-06T07:30:00+08:00"}],"sources":["open-meteo","qweather"]}"""
    val f = parseForecast(json)
    assertEquals(
      listOf(start to WeatherHour(1.0, -6.8, 9.5, 20.8, true, Sky.Cloudy), start + hour to WeatherHour(1.0, -6.8, 9.5, 20.8, true, Sky.Rain, 225.0)),
      f.hours,
    )
    assertEquals(listOf(OfficialAlert("a1", "t", "x", true, "萍乡市气象台", 1_791_243_000_000L)), f.alerts)
    assertEquals(listOf(true, false, 1900.0), listOf(f.ok, f.alertsFailed, f.elevation))
    val w = PlaceWeather(33.96, 107.77, null, start + 60_000, json)
    assertEquals(w, readWeather(writeWeather(w)))
    // Cached before GET /v1/weather (hours by point, from a start): no cache rather than a crash.
    assertNull(readWeather("""{"lat":33.96,"lon":107.77,"ele":null,"fetchedAt":$start,"start":$start,"response":"{\"hours\":[{\"point\":0}],\"warnings\":[],\"sources\":[]}"}"""))
  }

  @Test
  fun detailAddsCloudLayersCloudSeaThunderPotentialFreezingLevelAndProfile() {
    val json = """{"forecast":"ok","elevation":1900,"groundElevation":1200,"hours":[
      {"time":1790553600,"temp":1,"feelsLike":-2,"precip":0,"gust":5,"thunder":false,"sky":"clear","cloudLow":80,"cloudMid":20,"cloudHigh":0,
       "cloudSea":"high","cloudTop":1650,"thunderPotential":"medium","freezingLevel":3700,"profile":[{"height":1200,"temp":5,"rh":96}]}],
      "warnings":[],"sources":["open-meteo"]}"""
    val f = parseForecast(json)
    assertEquals(1200.0, f.groundElevation)
    assertEquals(
      WeatherHour(
        1.0, -2.0, 0.0, 5.0, false, Sky.Clear, cloudLow = 80.0, cloudMid = 20.0, cloudHigh = 0.0, cloudSea = Odds.High, cloudTop = 1650.0,
        thunderPotential = Odds.Medium, freezingLevel = 3700.0, profile = listOf(WeatherLevel(1200.0, 5.0, 96.0)),
      ),
      f.hours.single().second,
    )
    // Without detail (沿途 overviews), all of it left out.
    assertEquals(calm, place(calm).hours().single().second)
    assertNull(place(calm).forecast.groundElevation)
  }

  @Test
  fun aDaysProRowsAreThoseItsHoursHave() {
    fun rows(vararg hours: WeatherHour) = weatherDays(place(*hours), start, zone).single().proRows()
    val full = calm.copy(cloudLow = 10.0, cloudMid = 0.0, cloudHigh = 0.0, thunderPotential = Odds.Low, freezingLevel = 3700.0)
    // No 云海 grade any hour: no 云海 row.
    assertEquals(listOf(ProRow.Clouds, ProRow.Thunder, ProRow.Freezing), rows(full, full))
    assertEquals(ProRow.entries, rows(full, full.copy(cloudSea = Odds.Low)))
    // Without detail (沿途 overviews, or the profile failed): none.
    assertEquals(emptyList(), rows(calm, calm))
  }

  @Test
  fun freezingLevelInKilometresRedBelowThePlace() {
    assertEquals(listOf("3.7k", "0.9k", "12.0k"), listOf(3660.0, 940.0, 12_000.0).map(::freezingText))
    // The place is at 1000 m.
    val w = place(calm)
    assertEquals(listOf(true, false, false), listOf(999.0, 1000.0, null).map { w.freezesBelow(calm.copy(freezingLevel = it)) })
  }

  @Test
  fun profileSummarySaysHowFarTheCloudTopIsBelowThePlace() {
    // The place is at 1000 m.
    val w = place(calm)
    val sea = calm.copy(cloudSea = Odds.High, cloudTop = 650.0, thunderPotential = Odds.Medium, cloudLow = 80.0, cloudMid = 20.0, cloudHigh = 0.0)
    assertEquals("云海 高 · 云顶约 650 m，比你低 350 m · 雷暴潜势 中 · 云量 低 80% 中 20% 高 0%", w.profileSummary(sea))
    // No 云顶: not said; what's missing left out.
    assertEquals("雷暴潜势 低", w.profileSummary(calm.copy(thunderPotential = Odds.Low)))
  }

  @Test
  fun warningSaysWhoIssuedItAndWhen() {
    val a = OfficialAlert("a1", "t", "x", false, "萍乡市气象台", 1_791_243_000_000L)
    assertEquals("萍乡市气象台 · 10月6日 07:30 发布", a.issuedText(zone))
    assertEquals("10月6日 07:30 发布", a.copy(sender = null).issuedText(zone))
    assertEquals("萍乡市气象台", a.copy(issuedMs = null).issuedText(zone))
    assertNull(a.copy(sender = null, issuedMs = null).issuedText(zone))
  }

  @Test
  fun withoutAForecastTheCachedOneWithTheNewWarnings() {
    val cached = place(calm, calm).copy(fetchedMs = start - 3 * hour)
    val warning = OfficialAlert("a3", "暴雨蓝色预警", "", false)
    val got = PlaceWeather(33.96, 107.77, 1000.0, start, answer(emptyList(), listOf(warning), forecast = "quota_exhausted"))
    val shown = got.orCached(cached)
    assertEquals(listOf(start, start + hour), shown.hours().map { it.first })
    assertEquals(listOf(start - 3 * hour, true), listOf(shown.fetchedMs, shown.offline))
    assertEquals(listOf(warning), shown.alerts)
    assertEquals(Risk.Official, alerts(shown).single().risk)
    // No cache: the answer as it is, saying why there are no hours.
    assertEquals(got, got.orCached(null))
    assertFalse(got.forecast.ok)
    // A forecast that came is shown, whatever was cached.
    val fresh = place(calm)
    assertEquals(fresh, fresh.orCached(cached))
  }

  @Test
  fun warningsThatFailedAreSaid() {
    val failed = PlaceWeather(33.96, 107.77, null, start, answer(listOf(calm), extra = ""","warningsFailed":true"""))
    assertTrue(failed.alertsFailed)
    assertFalse(place(calm).alertsFailed)
    // Asked again on 重试 till both came.
    assertEquals(listOf(false, true), listOf(failed.complete, place(calm).complete))
    // Shown over a cached forecast, the new answer's say.
    assertTrue(PlaceWeather(33.96, 107.77, null, start, answer(emptyList(), forecast = "failed", extra = ""","warningsFailed":true""")).orCached(place(calm)).alertsFailed)
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
    assertEquals(listOf(20, 12, Sky.Clear, false), listOf(today.high, today.low, today.sky, today.stormy))
    assertEquals(listOf(Sky.Rain, true), listOf(tomorrow.sky, tomorrow.stormy))
    // Freezing alone isn't marked.
    assertFalse(weatherDays(place(calm.copy(feelsLike = -2.0)), start, zone).single().stormy)
    assertEquals(1.5, tomorrow.precip, 1e-9)
    // Later in the day, today starts from the current hour.
    assertEquals(4, weatherDays(place(*hours.toTypedArray()), start + 12 * hour, zone).first().hours.size)
  }

  @Test
  fun eachDayGetsItsConfidenceByItsDate() {
    // 08:00 on 09-28 through 07:00 on 09-30: three days on the strip; 09-30 isn't in days, 10-05 isn't on the strip.
    val days = ""","days":[{"date":"2026-09-28","confidence":"high"},{"date":"2026-09-29","confidence":"low","lowBy":["precip","gust"]},
      {"date":"2026-10-05","confidence":"medium","lowBy":["temp"]}]"""
    val w = PlaceWeather(33.96, 107.77, 1000.0, start, answer(List(48) { calm }, extra = days))
    val confidences = weatherDays(readWeather(writeWeather(w))!!, start, zone).map { it.confidence }
    assertEquals(listOf(DayConfidence(Confidence.High, emptyList()), DayConfidence(Confidence.Low, listOf(LowBy.Precip, LowBy.Gust)), null), confidences)
    assertEquals(listOf("可信度高", "可信度低：降水、阵风", "可信度暂缺"), confidences.map { confidenceText(it) })
    assertEquals(listOf(null, "信低", null), confidences.map { it?.tag })
    assertEquals("信中", DayConfidence(Confidence.Medium, listOf(LowBy.Temp)).tag)
    // Only the basic forecast: no days at all.
    assertNull(weatherDays(place(calm), start, zone).single().confidence)
  }

  @Test
  fun aDayIsStormyForHeavyRainOrAGaleButNotThunder() {
    assertEquals(
      listOf(true, false, true),
      listOf(
        weatherDays(place(calm.copy(precip = 8.0)), start, zone).single().stormy,
        weatherDays(place(calm.copy(thunder = true)), start, zone).single().stormy,
        weatherDays(place(calm.copy(gust = 17.2)), start, zone).single().stormy,
      ),
    )
  }

  /** 20 km due north in 100 m steps, highest 7.3 km in. */
  private val line = listOf((0..200).map { i -> TrackPoint(0, 30 + i * 0.1 / 111.195, 103.0, 2000 - kotlin.math.abs(i - 73) * 5.0) })

  @Test
  fun spotsAlongATrack() {
    val spots = trackSpots(line)
    assertEquals(listOf("起点", "最高点", "10 km", "15 km", "终点"), spots.map { it.label })
    assertEquals(7300.0, spots[1].distM, 1.0)
  }

  @Test
  fun aLongTrackKeepsAtMostEightSpots() = assertTrue(trackSpots(line, everyM = 500.0).size <= 8)

  @Test
  fun aLoopEndsWhereItStarts() {
    val loop = listOf(line[0] + line[0].reversed())
    assertEquals(listOf("起终点", "最高点"), trackSpots(loop).map { it.label }.filter { "km" !in it })
  }
}

class RiskHintTest {
  @Test
  fun riskHintNamesTheRiskOrTheOfficialWarning() {
    assertEquals("⚠ 3 小时内有雷暴", riskHint(TripAlert(Risk.Thunder, "")))
    assertEquals("⚠ 有官方天气预警", riskHint(TripAlert(Risk.Official, "暴雨蓝色预警")))
  }

  // C2-104, C2-105: wind in 级 (Beaufort), named for where it comes from.
  @Test
  fun windInBeaufortFromItsDirection() {
    assertEquals(0, beaufort(0.2))
    assertEquals(3, beaufort(5.0))
    assertEquals(4, beaufort(5.5))
    assertEquals(8, beaufort(17.2))
    assertEquals(12, beaufort(40.0))
    assertEquals("东北风 3 级", windText(45.0, 5.0))
    assertEquals("3 级", windText(null, 5.0))
  }
}
