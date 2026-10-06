package com.starsdom.trail.track

import com.starsdom.trail.Datum
import com.starsdom.trail.SYNC_ALL
import com.starsdom.trail.SyncGroup
import com.starsdom.trail.SyncTrack
import com.starsdom.trail.SyncWaypoint
import com.starsdom.trail.trackStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLibraryTest {
  private val db = TrackDb(RuntimeEnvironment.getApplication())
  private val photos = java.nio.file.Files.createTempDirectory("photos").toFile()
  private var exports = java.nio.file.Files.createTempDirectory("exports").toFile()

  @After fun close() = db.close()

  /** A library on the test's clock, its lists watched as the screen would. */
  private fun TestScope.library(): TrackLibrary = TrackLibrary(db, photos, exports, backgroundScope, StandardTestDispatcher(testScheduler)).also { lib ->
    backgroundScope.launch { merge(lib.tracks, lib.waypoints, lib.groups).collect {} }
  }

  private val line = ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null))))

  // ux-v3 §8.5 第 2 条: newest on this phone first, whenever it was walked; the row's date is still the walk's.
  @Test fun listedByWhenTheyCameHere() = runTest {
    val lib = library()
    val recorded = db.startTrack(5_000).also { db.endTrack(it, 6_000) }
    val old = db.importTrack(line, "老", emptyList(), 7_000)
    db.applyTrack(SyncTrack("u1", 500, 600, false, emptyList(), "pulled", Datum.WGS84, false, false))
    runCurrent()
    assertEquals(listOf(db.idOf("u1"), old, recorded), lib.tracks.value.map { it.id })
    assertEquals(listOf(500L, 1000L, 5_000L), lib.tracks.value.map { it.startedMs })
  }

  // The recording service writes on its own: the track it ends is listed, however late that comes.
  @Test fun aRecordingShowsOnceTheServiceEndsIt() = runTest {
    val lib = library()
    val id = db.startTrack(1_000)
    db.addPoint(id, 0, TrackPoint(1_000, 34.0, 108.0, null))
    runCurrent()
    assertEquals(emptyList<TrackSummary>(), lib.tracks.value)
    db.endTrack(id, 2_000)
    runCurrent()
    assertEquals(listOf(id), lib.tracks.value.map { it.id })
  }

  // So is a 标注 from the notification (C7-27).
  @Test fun aMarkFromTheServiceShows() = runTest {
    val lib = library()
    val w = db.addWaypoint(null, 1_000, 34.0, 108.0, null)
    runCurrent()
    assertEquals(listOf(w), lib.waypoints.value.map { it.id })
  }

  // What a pull brings shows at once: tracks, 标注组, their 标注.
  @Test fun aPullShows() = runTest {
    val lib = library()
    db.applyTrack(SyncTrack("u1", 500, 600, false, emptyList(), "pulled", Datum.WGS84, false, false))
    db.applyGroup(SyncGroup("g1", "水源", false))
    db.applyWaypoint(SyncWaypoint("w1", null, "g1", 0, 34.0, 108.0, null, "a", "", null, false)) { "" }
    runCurrent()
    assertEquals(listOf("pulled"), lib.tracks.value.map { it.name })
    assertEquals(listOf("水源" to 1), lib.groups.value.map { it.name to it.count })
    assertEquals(listOf("a"), lib.waypoints.value.map { it.name })
  }

  // Its own writes show too, the lists read again off the main thread.
  @Test fun itsOwnWritesShow() = runTest {
    val lib = library()
    val id = db.importTrack(line, "t", emptyList(), 0)
    lib.rename(id, "鳌太")
    val w = lib.addWaypoint(null, 0, 34.0, 108.0, null, "垭口")
    lib.setWaypointText(w.id, "垭", "")
    lib.setWaypointText(w.id, "垭口", "风大")
    runCurrent()
    assertEquals(listOf("鳌太"), lib.tracks.value.map { it.name })
    assertEquals(listOf("垭口" to "风大"), lib.waypoints.value.map { it.name to it.description })
  }

  // C2-73: 「已公开」 shows or goes in place.
  @Test fun publicShowsAtOnce() = runTest {
    val lib = library()
    val id = db.importTrack(line, "t", emptyList(), 0)
    runCurrent()
    db.setPublic(id, true)
    runCurrent()
    assertTrue(lib.tracks.value.single().public)
  }

  // 轨迹详情 all at once, read again as it changes: here (改名, 坐标纠偏) or by a pull.
  @Test fun detailFollowsWrites() = runTest {
    val lib = library()
    val id = db.importTrack(ParsedTrack("t", true, listOf(listOf(TrackPoint(0, 34.0, 108.0, null))), "来自 高驰"), "山", emptyList(), 0, imported = true)
    val seen = mutableListOf<TrackDetail?>()
    backgroundScope.launch { lib.detail(id).collect { seen += it } }
    runCurrent()
    val first = seen.last()!!
    assertEquals(listOf("山", "来自 高驰"), listOf(first.name, first.source))
    assertTrue(first.planned && first.imported && !first.public)
    assertEquals(listOf(listOf(TrackPoint(0, 34.0, 108.0, null))), first.segments)

    db.setName(id, "鳌太")
    db.setDatum(id, Datum.GCJ02)
    runCurrent()
    assertEquals("鳌太", seen.last()!!.name)
    assertEquals(Datum.GCJ02, seen.last()!!.datum)
    assertEquals(Datum.GCJ02.toWgs84(34.0, 108.0), seen.last()!!.segments.single().single().let { it.lat to it.lon })
    assertEquals(first.raw, seen.last()!!.raw)

    for (t in db.pendingTracks()) db.pushed("track", t.id, SYNC_ALL, t.edits)
    db.applyTrack(SyncTrack(db.uuid(id), 0, 0, true, emptyList(), "拉回", Datum.WGS84, true, false))
    runCurrent()
    assertEquals(listOf("拉回", true), seen.last()!!.let { listOf(it.name, it.public) })

    // A write elsewhere doesn't make it show again.
    val shown = seen.size
    db.addWaypoint(null, 0, 34.0, 108.0, null)
    runCurrent()
    assertEquals(shown, seen.size)

    db.deleteTrack(id)
    runCurrent()
    assertEquals(null, seen.last())
  }

  private val w = Waypoint(0, null, 0, 34.0, 108.0, null, "水源", "", null)
  private fun loose() = db.addWaypoint(null, 0, 34.0, 108.0, null)
  private fun shown() = db.waypoints().filter { it.shown }.map { it.id }.toSet()

  // 截取 (#88): a new private track of the points as stored, its 纠偏 and plan kept; the original as it was.
  @Test fun trimCopiesAPieceIntoANewTrack() = runTest {
    val lib = library()
    val segments = listOf(listOf(TrackPoint(0, 34.0, 108.0, 1.0), TrackPoint(0, 34.01, 108.0, 2.0)), listOf(TrackPoint(0, 34.02, 108.0, null), TrackPoint(0, 34.03, 108.0, null)))
    val id = db.importTrack(ParsedTrack("p", true, segments), "山", emptyList(), 0, imported = true)
    db.setDatum(id, Datum.GCJ02)
    db.setPublic(id, true)
    val photo = java.io.File.createTempFile("photo", ".jpg").apply { writeText("x") }
    val near = db.addWaypoint(id, 0, 34.01, 108.0, null).also { db.updateWaypoint(it, "垭口", "", photo.path) }
    db.addWaypoint(id, 0, 34.03, 108.0, null)
    val before = db.rawPoints(id)

    val piece = lib.trim(id, 1..2, "山 · 截取", "截取自「山」", 5)

    assertEquals(before.subList(1, 3).map { it.p }, db.rawPoints(piece).map { it.p })
    assertEquals(listOf(0, 1), db.rawPoints(piece).map { it.segment })
    assertEquals("山 · 截取", db.trackName(piece))
    assertEquals("截取自「山」", db.source(piece))
    assertEquals(Datum.GCJ02, db.datum(piece))
    assertTrue(db.planned(piece))
    assertTrue(db.imported(piece))
    assertFalse(db.isPublic(piece))
    val copied = db.waypoints(piece).single()
    assertEquals("垭口", copied.name)
    assertTrue(copied.photo != photo.path && java.io.File(copied.photo!!).readText() == "x")
    assertEquals(before, db.rawPoints(id))
    assertEquals(2, db.waypoints(id).size)
    assertTrue(db.isPublic(id))
    assertEquals(photo.path, db.waypoints(id).first { it.id == near }.photo)
    assertTrue(db.pendingTracks().any { it.id == piece })
  }


  // 合并 (#189): one 纠偏 kept with the points as stored; mixed, all to WGS-84. All 标注 along; the originals as they were.
  @Test fun mergeKeepsOneDatumAndConvertsMixed() = runTest {
    val lib = library()
    fun line(t: Long, lat: Double) = ParsedTrack("t", false, listOf(listOf(TrackPoint(t, lat, 108.0, null), TrackPoint(t + 1, lat + 0.01, 108.0, null))))
    val a = db.importTrack(line(1000, 34.0), "甲", emptyList(), 0, imported = true)
    val b = db.importTrack(line(2000, 34.1), "乙", emptyList(), 0, imported = true)
    for (id in listOf(a, b)) db.setDatum(id, Datum.GCJ02)
    db.addWaypoint(a, 1000, 34.0, 108.0, null)
    db.addWaypoint(b, 2000, 34.1, 108.0, null)
    db.setPublic(a, true)
    val before = db.rawPoints(a)

    val same = lib.merge(listOf(a, b), "甲 · 合并", "合并自 2 条轨迹", 5)
    assertEquals(Datum.GCJ02, db.datum(same))
    assertEquals((db.rawPoints(a) + db.rawPoints(b)).map { it.p }, db.rawPoints(same).map { it.p })
    assertEquals(listOf(0, 0, 1, 1), db.rawPoints(same).map { it.segment })
    assertEquals(2, db.waypoints(same).size)
    assertEquals("合并自 2 条轨迹", db.source(same))
    assertFalse(db.isPublic(same))
    assertFalse(db.planned(same))

    db.setDatum(b, Datum.WGS84)
    val mixed = lib.merge(listOf(a, b), "x", "s", 5)
    assertEquals(Datum.WGS84, db.datum(mixed))
    assertEquals((db.segments(a) + db.segments(b)).flatten(), db.rawPoints(mixed).map { it.p })
    assertEquals(db.waypoints(a).map { it.lat to it.lon }, db.waypoints(mixed).take(1).map { it.lat to it.lon })
    assertFalse(db.imported(mixed))

    assertEquals(before, db.rawPoints(a))
    assertTrue(db.isPublic(a))
    assertEquals(1, db.waypoints(a).size)
  }


  @Test fun mergedPlansAreOneLine() = runTest {
    val lib = library()
    val plan = ParsedTrack("p", true, listOf(listOf(TrackPoint(0, 34.0, 108.0, null), TrackPoint(0, 34.01, 108.0, null))))
    val id = lib.merge(listOf(db.importTrack(plan, "a", emptyList(), 0), db.importTrack(plan, "b", emptyList(), 0)), "a · 合并", "s", 5)
    assertTrue(db.planned(id))
    assertEquals(listOf(0, 0, 0, 0), db.rawPoints(id).map { it.segment })
  }


  // Plans aren't held to times, even ones a file gave them.
  @Test fun onlyTracksTimesMayOverlap() = runTest {
    val lib = library()
    fun line(planned: Boolean) = ParsedTrack("p", planned, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null), TrackPoint(2000, 34.01, 108.0, null))))
    assertFalse(lib.mergeOverlaps(listOf(db.importTrack(line(true), "a", emptyList(), 0), db.importTrack(line(true), "b", emptyList(), 0))))
    assertTrue(lib.mergeOverlaps(listOf(db.importTrack(line(false), "c", emptyList(), 0), db.importTrack(line(false), "d", emptyList(), 0))))
  }

  @Test fun renamingToATakenNameIsRefused() = runTest {
    val lib = library()
    val a = lib.addGroup("A")!!
    lib.addGroup("B")!!
    assertEquals(null, lib.addGroup("A"))
    assertFalse(lib.renameGroup(a, "B"))
    assertTrue(lib.renameGroup(a, "C"))
    assertEquals(listOf("B", "C"), db.groups().map { it.name })
  }


  @Test fun movingIntoAHiddenGroupShowsIt() = runTest {
    val lib = library()
    val g = lib.addGroup("G")!!
    val (a, b) = loose() to loose()
    lib.moveWaypoint(a, g)
    lib.setGroupShown(g, false)
    assertEquals(setOf(b), shown())
    lib.moveWaypoint(b, g)
    assertEquals(setOf(a, b), shown())
    assertTrue(db.groups().single().shown)
  }


  @Test fun looseWaypointsToggleOneByOne() = runTest {
    val lib = library()
    val (a, b) = loose() to loose()
    lib.setWaypointShown(a, false)
    assertEquals(setOf(b), shown())
  }


  @Test fun aTracksWaypointStaysOutOfGroups() = runTest {
    val lib = library()
    val t = db.importTrack(ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null)))), "t", listOf(w), 0)
    val g = lib.addGroup("G")!!
    lib.moveWaypoint(db.waypoints().single().id, g)
    assertEquals(listOf(t to null), db.waypoints().map { it.trackId to it.groupId })
  }


  @Test fun aPulledWaypointMovedOutOfAGroupShows() = runTest {
    val lib = library()
    db.applyWaypoint(SyncWaypoint("w1", null, null, 0, 34.0, 108.0, null, "a", "", null, false)) { "" }
    val id = db.waypoints().single().id
    lib.moveWaypoint(id, lib.addGroup("G")!!)
    lib.moveWaypoint(id, null)
    assertEquals(setOf(id), shown())
  }

  // §8.5 第 15 条: deleted, a track is hidden with its 标注 until 撤销 is over; 撤销 brings it all back.
  @Test fun aDeletedTrackHidesThenComesBackWhole() = runTest {
    val lib = library()
    val id = db.importTrack(line, "t", emptyList(), 0)
    val photo = java.io.File.createTempFile("photo", ".jpg")
    val w = db.addWaypoint(id, 1500, 34.0, 108.0, null).also { db.updateWaypoint(it, "垭口", "", photo.path) }
    val deleted = lib.delete(Trash.Track, id)
    runCurrent()
    assertEquals(emptyList<TrackSummary>(), lib.tracks.value)
    assertEquals(emptyList<Waypoint>(), lib.waypoints.value)
    assertEquals(KnownTracks(db.version.value, setOf(id), setOf(id)), lib.known.first { it != null && lib.current(it) })
    deleted.undo()
    advanceTimeBy(UNDO_MS)
    runCurrent()
    assertEquals(listOf(id), lib.tracks.value.map { it.id })
    assertEquals(listOf(w to photo.path), lib.waypoints.value.map { it.id to it.photo })
    assertTrue(photo.exists())
  }

  // Once 撤销 is over it's gone for good, photos too; only what was deleted then.
  @Test fun deletedForGoodOnceUndoIsOver() = runTest {
    val lib = library()
    val (a, b) = db.importTrack(line, "a", emptyList(), 0) to db.importTrack(line, "b", emptyList(), 0)
    val photo = java.io.File.createTempFile("photo", ".jpg")
    db.addWaypoint(a, 1500, 34.0, 108.0, null).also { db.updateWaypoint(it, "", "", photo.path) }
    lib.delete(Trash.Track, a)
    advanceTimeBy(1_000)
    lib.delete(Trash.Track, b)
    advanceTimeBy(UNDO_MS - 1_000)
    assertEquals(listOf(a, b), db.trashedTracks())
    runCurrent()
    assertFalse(photo.exists())
    assertEquals(emptyList<Any>(), db.rawPoints(a))
    assertEquals(setOf(b), lib.known.first { it != null && lib.current(it) }!!.all)
  }

  // Killed while 撤销 was on offer: deleted for good when the app (its 轨迹库) next starts, never left half-deleted.
  @Test fun whatAKilledAppLeftDeletedGoesOnTheNextStart() = runTest {
    val id = db.importTrack(line, "t", emptyList(), 0)
    library().delete(Trash.Track, id)
    library()
    runCurrent()
    assertEquals(emptyList<Long>(), db.trashedTracks())
    assertEquals(emptyList<Any>(), db.rawPoints(id))
  }

  @Test fun aDeletedGroupHidesItsWaypointsAndADeletedWaypointLeavesItsCount() = runTest {
    val lib = library()
    val g = db.importGroup("X", listOf(w, w))
    val loose = loose()
    lib.delete(Trash.Waypoint, db.waypoints().first { it.groupId == g }.id)
    runCurrent()
    assertEquals(listOf(1), lib.groups.value.map { it.count })
    lib.delete(Trash.Group, g).undo()
    lib.delete(Trash.Group, g)
    runCurrent()
    assertEquals(emptyList<WaypointGroup>(), lib.groups.value)
    assertEquals(listOf(loose), lib.waypoints.value.map { it.id })
  }

  private fun gpx(body: String) = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>""".toByteArray()
  private fun trk(name: String) = """<trk><name>$name</name><trkseg><trkpt lat="34.0" lon="108.0"/><trkpt lat="34.01" lon="108.0"/></trkseg></trk>"""
  private val wpt = """<wpt lat="34.0" lon="108.0"><name>垭口</name></wpt>"""
  private suspend fun TrackLibrary.file(bytes: ByteArray) = (read { bytes.inputStream() } as Read.Ok).file

  // §2.6: what's wrong with a file, each said its own way.
  @Test fun readsAFileOrSaysWhatsWrong() = runTest {
    val lib = library()
    assertEquals(Read.Unreadable, lib.read { "not a track".byteInputStream() })
    assertEquals(Read.Unreadable, lib.read { throw java.io.FileNotFoundException() })
    assertEquals(Read.Empty, lib.read { gpx("").inputStream() })
    val tooBig = object : java.io.InputStream() {
      var left = MAX_TRACK_FILE_BYTES + 1
      override fun read() = if (left-- > 0) 0 else -1
      override fun read(b: ByteArray, off: Int, len: Int) = if (left <= 0) -1 else minOf(len.toLong(), left).toInt().also { left -= it }
    }
    assertEquals(Read.TooBig, lib.read { tooBig })
    assertEquals(listOf("a"), lib.file(gpx(trk("a") + wpt)).tracks.map { it.name })
  }

  // Only 标注: a 标注组 named after the file (#121), numbered if that's taken; deleting one leaves the other's.
  @Test fun onlyWaypointsMakeAGroupNumberedIfTaken() = runTest {
    val lib = library()
    val file = lib.file(gpx(wpt + wpt))
    val x = lib.import("X.gpx", file, emptyList())
    val x1 = lib.import("X.gpx", file, emptyList())
    runCurrent()
    assertEquals(listOf("X" to 2, "X (1)" to 2), lib.groups.value.map { it.name to it.count })
    assertEquals(Imported(emptyList(), 2, x.group, 0.0), x)
    lib.delete(Trash.Group, x.group!!)
    advanceTimeBy(UNDO_MS)
    runCurrent()
    assertEquals(listOf(x1.group), lib.groups.value.map { it.id })
    assertEquals(listOf(x1.group, x1.group), lib.waypoints.value.map { it.groupId })
  }

  // With tracks, those picked: each named after the file and its own name or number (#123), the 标注 with the first.
  @Test fun pickedTracksTakeTheFilesNameAndTheFirstItsWaypoints() = runTest {
    val lib = library()
    val file = lib.file(gpx(wpt + trk("a") + trk("") + trk("c")))
    val r = lib.import("鳌太.gpx", file, listOf(1, 2))
    runCurrent()
    assertEquals(listOf("鳌太 · c", "鳌太 2"), lib.tracks.value.map { it.name })
    assertEquals(listOf(r.tracks.first()), lib.waypoints.value.map { it.trackId })
    assertTrue(r.tracks.all(db::imported))
    assertEquals(2 * trackStats(file.tracks[1].segments).distanceM, r.distanceM, 0.01)
    assertEquals(listOf("鳌太"), lib.import("鳌太.gpx", lib.file(gpx(trk("a"))), listOf(0)).tracks.map(db::trackName))
  }

  // Our own zip export: each 标注 gets its photo back, a file of its own here.
  @Test fun photosComeAlongFromOurZip() = runTest {
    val lib = library()
    val zip = java.io.ByteArrayOutputStream().also { out ->
      java.util.zip.ZipOutputStream(out).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("t.gpx"))
        z.write(gpx("""<wpt lat="34.0" lon="108.0"><name>垭口</name><link href="photos/a.jpg"/></wpt>"""))
        z.putNextEntry(java.util.zip.ZipEntry("photos/a.jpg"))
        z.write("x".toByteArray())
      }
    }.toByteArray()
    lib.import("t.zip", lib.file(zip), emptyList())
    runCurrent()
    val photo = java.io.File(lib.waypoints.value.single().photo!!)
    assertEquals(photos, photo.parentFile)
    assertEquals("x", photo.readText())
  }

  // A 周边路网 line or my copy of a 队伍轨迹: one save, made once per uuid.
  @Test fun savedOncePerUuid() = runTest {
    val lib = library()
    val a = lib.save(line, "路网轨迹")
    val copy = lib.save(line, "队伍", uuid = "u1")
    assertEquals(copy, lib.save(line, "队伍", uuid = "u1"))
    runCurrent()
    assertEquals(listOf(copy, a), lib.tracks.value.map { it.id })
    assertFalse(db.imported(copy))
  }

  // 导出 (§8.5 第 14 条): GPX under its name (#146); with photos, a zip our own import takes back whole.
  @Test fun aTrackWithPhotosGoesOutAsAZip() = runTest {
    val lib = library()
    val id = db.importTrack(ParsedTrack("t", false, listOf(listOf(TrackPoint(1000, 34.0, 108.0, null), TrackPoint(2000, 34.01, 108.0, null)))), "鳌/太", emptyList(), 0)
    val photo = java.io.File(photos, "a.jpg").apply { writeText("x") }
    db.addWaypoint(id, 1500, 34.0, 108.0, null).also { db.updateWaypoint(it, "垭口", "", photo.path) }
    val zip = lib.exportTrack(id, kml = false) as Export.Ok
    assertEquals("鳌_太.zip" to "application/zip", zip.file.name to zip.type)
    val back = parseTrackFile(zip.file.readBytes())
    assertEquals(listOf("鳌/太"), back.tracks.map { it.name })
    assertEquals(listOf("垭口" to "photos/a.jpg"), back.waypoints.map { it.name to it.photo })
    assertEquals("x", String(back.photos.getValue("photos/a.jpg")))
    // KML takes no photos.
    val kml = lib.exportTrack(id, kml = true) as Export.Ok
    assertEquals("鳌_太.kml" to "application/vnd.google-earth.kml+xml", kml.file.name to kml.type)
    assertFalse(kml.file.readText().contains("a.jpg"))
  }

  // #72: a 标注组's 标注, or those 不在组里; without photos, just a GPX.
  @Test fun waypointsGoOutByGroupOrLoose() = runTest {
    val lib = library()
    val g = db.importGroup("水源", listOf(w))
    db.addWaypoint(null, 0, 34.1, 108.0, null).also { db.updateWaypoint(it, "营地", "", null) }
    val group = lib.exportWaypoints(g, "水源", kml = false) as Export.Ok
    assertEquals("水源.gpx" to "application/gpx+xml", group.file.name to group.type)
    assertEquals(listOf("水源"), parseTrackFile(group.file.readBytes()).waypoints.map { it.name })
    val loose = lib.exportWaypoints(null, "标注 10月5日", kml = false) as Export.Ok
    assertEquals(listOf("营地"), parseTrackFile(loose.file.readBytes()).waypoints.map { it.name })
  }

  @Test fun anExportThatCantBeWrittenSaysSo() = runTest {
    exports = java.io.File.createTempFile("exports", "")
    val lib = library()
    assertEquals(Export.Failed, lib.exportTrack(db.importTrack(line, "t", emptyList(), 0), kml = false))
  }

  // Failing partway (here, a track picked that isn't there), an import leaves nothing behind, so 重试 doesn't double it.
  @Test fun aFailedImportLeavesNothing() = runTest {
    val lib = library()
    val zip = java.io.ByteArrayOutputStream().also { out ->
      java.util.zip.ZipOutputStream(out).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("t.gpx"))
        z.write(gpx("""<wpt lat="34.0" lon="108.0"><link href="photos/a.jpg"/></wpt>""" + trk("a")))
        z.putNextEntry(java.util.zip.ZipEntry("photos/a.jpg"))
        z.write("x".toByteArray())
      }
    }.toByteArray()
    val file = lib.file(zip)
    val before = db.version.value
    assertTrue(runCatching { lib.import("t.zip", file, listOf(0, 5)) }.isFailure)
    assertEquals(before, db.version.value)
    assertEquals(emptyList<TrackSummary>(), db.tracks())
    assertEquals(emptyList<Waypoint>(), db.waypoints())
    assertEquals(emptyList<String>(), photos.list()!!.toList())
  }

  // What a write does is told once, after the outermost transaction is in: nothing is read again half-written.
  @Test fun aWriteIsToldOnceItsIn() = runTest {
    val before = db.version.value
    db.atomically {
      val id = db.importTrack(line, "t", listOf(w, w), 0)
      db.setName(id, "鳌太")
      assertEquals(before, db.version.value)
    }
    assertEquals(before + 1, db.version.value)
    db.importTrack(line, "u", listOf(w), 0)
    assertEquals(before + 2, db.version.value)
  }
}
