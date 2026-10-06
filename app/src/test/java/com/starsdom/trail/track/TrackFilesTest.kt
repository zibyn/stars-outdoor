package com.starsdom.trail.track

import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.GarminProduct
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilesTest {
  private val t0 = 1790000000000

  @Test
  fun gpxExportReimportKeepsTrackAndWaypoints() {
    val segments = listOf(listOf(TrackPoint(t0, 33.96, 107.77, 1234.5)), listOf(TrackPoint(t0 + 5000, 33.961, 107.771, null)))
    val wpt = Waypoint(0, null, t0, 33.9, 107.7, 1500.0, "垭口 & 水源", "左侧<小路>", null)
    val file = parseTrackFile(toGpx("太白 & 鳌山", segments, listOf(wpt)).toByteArray())
    assertEquals(listOf(ParsedTrack("太白 & 鳌山", planned = false, segments)), file.tracks)
    assertEquals(listOf(wpt), file.waypoints)
  }

  @Test
  fun gpxRteIsPlannedAndEachTrkIsItsOwnTrack() {
    val gpx = """
      <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1">
        <trk><name>A</name><trkseg><trkpt lat="1" lon="2"><time>2026-09-21T14:13:20+08:00</time></trkpt></trkseg></trk>
        <trk><name>B</name><trkseg><trkpt lat="3" lon="4"/></trkseg></trk>
        <rte><name>计划</name><rtept lat="5" lon="6"><name>not a track name</name></rtept><rtept lat="7" lon="8"/></rte>
      </gpx>
    """.trimIndent()
    val tracks = parseTrackFile(gpx.toByteArray()).tracks
    assertEquals(listOf("A", "B", "计划"), tracks.map { it.name })
    assertEquals(listOf(false, false, true), tracks.map { it.planned })
    assertEquals(t0 - 8 * 3600_000, tracks[0].segments[0][0].timeMs)
    assertEquals(0L, tracks[1].segments[0][0].timeMs)
    assertEquals(listOf(TrackPoint(0, 5.0, 6.0, null), TrackPoint(0, 7.0, 8.0, null)), tracks[2].segments.single())
  }

  @Test
  fun gpxWaypointPhotoLinkRoundTrips() {
    val wpt = Waypoint(0, null, t0, 1.0, 2.0, null, "", "", "photos/1.jpg")
    assertEquals("photos/1.jpg", parseTrackFile(toGpx("x", emptyList(), listOf(wpt)).toByteArray()).waypoints.single().photo)
  }

  @Test
  fun kmlPlacemarksBecomeTracksAndWaypoints() {
    val kml = """
      <?xml version="1.0" encoding="UTF-8"?>
      <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>doc</name><Folder>
        <Placemark><name>营地</name><description>水源</description><TimeStamp><when>2026-09-21T14:13:20Z</when></TimeStamp>
          <Point><coordinates>107.77,33.96,1234</coordinates></Point></Placemark>
        <Placemark><name>线</name><MultiGeometry>
          <LineString><coordinates>107.1,33.1,100 107.2,33.2</coordinates></LineString>
          <LineString><coordinates>
            107.3,33.3,300
          </coordinates></LineString>
        </MultiGeometry></Placemark>
        <Placemark><name>gx</name><gx:Track><when>2026-09-21T14:13:20Z</when><gx:coord>107.5 33.5 500</gx:coord></gx:Track></Placemark>
      </Folder></Document></kml>
    """.trimIndent()
    val file = parseTrackFile(kml.toByteArray())
    assertEquals(listOf(Waypoint(0, null, t0, 33.96, 107.77, 1234.0, "营地", "水源", null)), file.waypoints)
    assertEquals(
      listOf(
        ParsedTrack("线", false, listOf(listOf(TrackPoint(0, 33.1, 107.1, 100.0), TrackPoint(0, 33.2, 107.2, null)), listOf(TrackPoint(0, 33.3, 107.3, 300.0)))),
        ParsedTrack("gx", false, listOf(listOf(TrackPoint(t0, 33.5, 107.5, 500.0)))),
      ),
      file.tracks,
    )
  }

  @Test
  fun kmlExportReimportKeepsWaypoints() {
    val wpt = Waypoint(0, null, t0, 33.9, 107.7, 1500.0, "垭口", "说明", null)
    val file = parseTrackFile(toKml("线", listOf(listOf(TrackPoint(t0, 33.96, 107.77, 12.0))), listOf(wpt)).toByteArray())
    assertEquals(listOf(wpt), file.waypoints)
    assertEquals(listOf(ParsedTrack("线", false, listOf(listOf(TrackPoint(0, 33.96, 107.77, 12.0))))), file.tracks)
  }

  @Test
  fun waypointsOnlyExportHasNoEmptyTrackAndReimports() {
    val wpts = listOf(Waypoint(0, null, t0, 33.9, 107.7, 1500.0, "垭口", "说明", null), Waypoint(0, null, t0 + 1000, 34.0, 107.8, null, "营地", "", null))
    val gpx = toGpx("组 A", emptyList(), wpts)
    assertTrue("<trk" !in gpx)
    assertTrue("<metadata><name>组 A</name></metadata>" in gpx)
    assertEquals(wpts, parseTrackFile(gpx.toByteArray()).waypoints)
    assertEquals(emptyList<ParsedTrack>(), parseTrackFile(gpx.toByteArray()).tracks)
    val kml = toKml("组 A", emptyList(), wpts)
    assertTrue("MultiGeometry" !in kml)
    assertEquals(2, Regex("<Point>").findAll(kml).count())
    assertTrue("<name>组 A</name>" in kml)
    assertEquals(wpts, parseTrackFile(kml.toByteArray()).waypoints)
    assertEquals(emptyList<ParsedTrack>(), parseTrackFile(kml.toByteArray()).tracks)
  }

  @Test
  fun zipReadsKmzAndGpxWithPhotos() {
    val kml = "<kml><Placemark><name>p</name><Point><coordinates>1,2</coordinates></Point></Placemark></kml>"
    assertEquals("p", parseTrackFile(zip("doc.kml" to kml.toByteArray())).waypoints.single().name)

    val gpx = toGpx("x", emptyList(), listOf(Waypoint(0, null, t0, 1.0, 2.0, null, "", "", "photos/a.jpg")))
    val file = parseTrackFile(zip("track.gpx" to gpx.toByteArray(), "photos/a.jpg" to byteArrayOf(1, 2, 3)))
    assertEquals("photos/a.jpg", file.waypoints.single().photo)
    assertEquals(listOf<Byte>(1, 2, 3), file.photos["photos/a.jpg"]!!.toList())
  }

  @Test
  fun geoJson() {
    val json = """
      {"type":"FeatureCollection","features":[
        {"type":"Feature","properties":{"name":"点","desc":"d","time":"2026-09-21T14:13:20Z"},"geometry":{"type":"Point","coordinates":[107.77,33.96,1234]}},
        {"type":"Feature","properties":{"name":"线"},"geometry":{"type":"LineString","coordinates":[[107.1,33.1],[107.2,33.2,5]]}},
        {"type":"Feature","properties":null,"geometry":{"type":"MultiLineString","coordinates":[[[1,2]],[[3,4]]]}}
      ]}
    """.trimIndent()
    val file = parseTrackFile(json.toByteArray())
    assertEquals(listOf(Waypoint(0, null, t0, 33.96, 107.77, 1234.0, "点", "d", null)), file.waypoints)
    assertEquals(listOf("线", ""), file.tracks.map { it.name })
    assertEquals(listOf(TrackPoint(0, 33.1, 107.1, null), TrackPoint(0, 33.2, 107.2, 5.0)), file.tracks[0].segments.single())
    assertEquals(2, file.tracks[1].segments.size)
  }

  @Test
  fun plt() {
    val plt = """
      OziExplorer Track Point File Version 2.1
      WGS 84
      Altitude is in Feet
      Reserved 3
      0,2,255,鳌太线,0,0,2,8421376
      3
        33.960000, 107.770000,1, 1000.0,45000.5,21-Mar-23, 12:00:00
        33.961000, 107.771000,0, -777,45000.5,21-Mar-23, 12:00:00
        33.962000, 107.772000,1, 0,0,,
    """.trimIndent().replace("\n", "\r\n")
    val track = parseTrackFile(plt.toByteArray()).tracks.single()
    assertEquals("鳌太线", track.name)
    val ms = (45000.5 - 25569) * 86_400_000
    assertEquals(
      listOf(
        listOf(TrackPoint(ms.toLong(), 33.96, 107.77, 304.8), TrackPoint(ms.toLong(), 33.961, 107.771, null)),
        listOf(TrackPoint(0, 33.962, 107.772, 0.0)),
      ),
      track.segments,
    )
  }

  @Test
  fun fitRecords() {
    val tmp = File.createTempFile("track", ".fit")
    FileEncoder(tmp, Fit.ProtocolVersion.V2_0).apply {
      write(RecordMesg().apply {
        timestamp = DateTime(1_000_000_000L)
        positionLat = (33.96 / 180 * 2147483648.0).toInt()
        positionLong = (107.77 / 180 * 2147483648.0).toInt()
        enhancedAltitude = 1234.5f
      })
      write(RecordMesg().apply { timestamp = DateTime(1_000_000_005L) }) // no fix: skipped
      close()
    }
    val p = parseTrackFile(tmp.readBytes().also { tmp.delete() }).tracks.single().segments.single().single()
    assertEquals((1_000_000_000L + 631065600L) * 1000, p.timeMs)
    assertEquals(33.96, p.lat, 1e-6)
    assertEquals(107.77, p.lon, 1e-6)
    assertEquals(1234.5, p.ele!!, 0.2) // FIT altitude resolution is 0.2 m
  }

  // #89: a watch's FIT says who made it; the model too when we can read it.
  @Test
  fun fitSource() {
    fun fit(manufacturer: Int, product: Int?, productName: String? = null): String? {
      val tmp = File.createTempFile("track", ".fit")
      FileEncoder(tmp, Fit.ProtocolVersion.V2_0).apply {
        write(FileIdMesg().apply { this.manufacturer = manufacturer; this.product = product; this.productName = productName })
        write(RecordMesg().apply { timestamp = DateTime(1_000_000_000L); positionLat = 0; positionLong = 0 })
        close()
      }
      return parseTrackFile(tmp.readBytes().also { tmp.delete() }).tracks.single().source
    }
    assertEquals("来自 佳明 fēnix 7", fit(Manufacturer.GARMIN, GarminProduct.FENIX7))
    assertEquals("来自 佳明 fēnix 7", fit(Manufacturer.GARMIN, GarminProduct.FENIX7_APAC))
    assertEquals("来自 佳明 Forerunner 265", fit(Manufacturer.GARMIN, GarminProduct.FR265_SMALL))
    assertEquals("来自 佳明 fēnix 7S Pro Solar", fit(Manufacturer.GARMIN, GarminProduct.FENIX7S_PRO_SOLAR))
    assertEquals("来自 佳明", fit(Manufacturer.GARMIN, 65000))
    assertEquals("来自 高驰", fit(Manufacturer.COROS, 12))
    assertEquals("来自 高驰 VERTIX 2", fit(Manufacturer.COROS, 12, "COROS VERTIX 2"))
    assertEquals("来自 颂拓", fit(Manufacturer.SUUNTO, null))
    assertEquals(null, fit(Manufacturer.DEVELOPMENT, null))
    assertEquals(null, parseTrackFile(toGpx("t", listOf(listOf(TrackPoint(0, 1.0, 2.0, null)))).toByteArray()).tracks.single().source)
  }

  @Test
  fun plannedExportsAsRteAndStaysPlanned() {
    val points = listOf(TrackPoint(0, 1.0, 2.0, 3.0), TrackPoint(0, 4.0, 5.0, null))
    assertEquals(listOf(ParsedTrack("计划", true, listOf(points))), parseTrackFile(toGpx("计划", listOf(points), planned = true).toByteArray()).tracks)
  }

  @Test
  fun zipInflatingPast50MbIsRejected() {
    val big = ByteArray((MAX_TRACK_FILE_BYTES + 1).toInt())
    assertTrue(runCatching { parseTrackFile(zip("track.gpx" to "<gpx/>".toByteArray(), "photos/a.jpg" to big)) }.isFailure)
  }

  @Test
  fun dropsTracksWithoutPoints() {
    assertEquals(emptyList<ParsedTrack>(), parseTrackFile("OziExplorer Track Point File Version 2.1\nWGS 84\n\n\n0,2,255,x\n0\n".toByteArray()).tracks)
  }

  @Test
  fun rejectsUnknownContent() {
    assertTrue(runCatching { parseTrackFile("hello".toByteArray()) }.isFailure)
  }

  @Test
  fun importNameUsesFileName() {
    fun t(name: String) = ParsedTrack(name, false, emptyList())
    assertEquals("武功山", importName(t("导航线片段1"), "武功山.gpx", 0, 1))
    assertEquals("武功山 · D1", importName(t("D1"), "武功山.gpx", 0, 2))
    assertEquals("武功山 2", importName(t(""), "武功山.gpx", 1, 2))
  }

  private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes) } }
  }.toByteArray()
}
