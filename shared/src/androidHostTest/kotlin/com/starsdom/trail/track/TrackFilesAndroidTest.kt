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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Zip and FIT, read the Android way (ZipInputStream, Garmin's Java SDK).
class TrackFilesAndroidTest {
  private val t0 = 1790000000000

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
  fun zipInflatingPast50MbIsRejected() {
    val big = ByteArray((MAX_TRACK_FILE_BYTES + 1).toInt())
    assertTrue(runCatching { parseTrackFile(zip("track.gpx" to "<gpx/>".toByteArray(), "photos/a.jpg" to big)) }.isFailure)
  }

  // Chinese tools write GBK, saying so in the declaration (XML) or not at all (PLT).
  @Test
  fun gbkXmlAndPlt() {
    val gpx = "<?xml version=\"1.0\" encoding=\"GBK\"?><gpx><wpt lat=\"1\" lon=\"2\"><name>鳌太线</name></wpt></gpx>"
    assertEquals("鳌太线", parseTrackFile(gpx.toByteArray(charset("GBK"))).waypoints.single().name)
    val plt = "OziExplorer Track Point File Version 2.1\nWGS 84\nAltitude is in Feet\nReserved 3\n0,2,255,鳌太线,0\n1\n33.96,107.77,1,0,0\n"
    assertEquals("鳌太线", parseTrackFile(plt.toByteArray(charset("GBK"))).tracks.single().name)
  }

  private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes) } }
  }.toByteArray()
}
