package com.starsdom.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
  @Test
  fun writesOneTrksegPerSegment() {
    val gpx = toGpx(
      "2026-09-27 08:00",
      listOf(
        listOf(TrackPoint(timeMs = 1790000000000, lat = 33.96, lon = 107.77, ele = 1234.5)),
        listOf(TrackPoint(timeMs = 1790000005000, lat = 33.961, lon = 107.771, ele = null)),
      ),
    )
    assertEquals(
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <gpx version="1.1" creator="Stars Trail" xmlns="http://www.topografix.com/GPX/1/1">
      <trk><name>2026-09-27 08:00</name><trkseg>
      <trkpt lat="33.96" lon="107.77"><ele>1234.5</ele><time>2026-09-21T14:13:20Z</time></trkpt>
      </trkseg><trkseg>
      <trkpt lat="33.961" lon="107.771"><time>2026-09-21T14:13:25Z</time></trkpt>
      </trkseg></trk>
      </gpx>
      """.trimIndent() + "\n",
      gpx,
    )
  }

  @Test
  fun escapesName() {
    assertTrue("<name>A &amp; &lt;B&gt;</name>" in toGpx("A & <B>", emptyList()))
  }

  @Test
  fun neverUsesScientificNotation() {
    assertTrue("<trkpt lat=\"0.000010\" lon=\"-0.00050\"><ele>0.00010</ele>" in toGpx("x", listOf(listOf(TrackPoint(0, 0.00001, -0.0005, 0.0001)))))
  }

  @Test
  fun writesTrackWaypointsBeforeTrk() {
    val gpx = toGpx(
      "x",
      emptyList(),
      listOf(
        Waypoint(id = 1, trackId = 7, timeMs = 1790000000000, lat = 33.96, lon = 107.77, ele = 1234.5, name = "垭口 & 水源", description = "左侧<小路>", photo = null),
        Waypoint(id = 2, trackId = 7, timeMs = 1790000005000, lat = 33.961, lon = 107.771, ele = null, name = "", description = "", photo = "/p.jpg"),
      ),
    )
    assertTrue(
      gpx.contains(
        "<wpt lat=\"33.96\" lon=\"107.77\"><ele>1234.5</ele><time>2026-09-21T14:13:20Z</time><name>垭口 &amp; 水源</name><desc>左侧&lt;小路&gt;</desc></wpt>\n" +
          "<wpt lat=\"33.961\" lon=\"107.771\"><time>2026-09-21T14:13:25Z</time><link href=\"/p.jpg\"/></wpt>\n<trk>"
      )
    )
  }
}
