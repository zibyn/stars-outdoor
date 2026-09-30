package com.starsdom.outdoor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTest {
  private val track = PendingTrack(1, "t1", synced = true, dirty = SYNC_NAME, edits = 3, startedAt = 1000, endedAt = 2000, planned = false, name = "鳌太线", datum = "GCJ02", public = true, deleted = false)
  private val wpt = PendingWaypoint(
    1, "w1", synced = true, dirty = 0, edits = 0, deleted = false, track = "t1", group = null, timeMs = 1500, lat = 34.0, lon = 108.0, ele = null,
    name = "垭口", description = "", photo = "/p.jpg", photoId = "abc",
  )

  private fun json(s: String) = Json.parseToJsonElement(s) as JsonObject

  @Test fun knownTrackSendsOnlyChangedAttributes() {
    assertEquals(json("""{"id":"t1","name":"鳌太线"}""") to SYNC_NAME, trackChange(track, null))
    assertEquals(json("""{"id":"t1","name":"","datum":"GCJ02","public":true}""") to SYNC_TRACK, trackChange(track.copy(dirty = SYNC_ALL, name = null), null))
    assertEquals(json("""{"id":"t1","public":false}""") to SYNC_PUBLIC, trackChange(track.copy(dirty = SYNC_PUBLIC, public = false), null))
  }

  @Test fun newTrackCarriesItsPoints() {
    val (change, bits) = trackChange(track.copy(synced = false, dirty = 0), listOf(SyncPoint(0, TrackPoint(1000, 34.0, 108.0, 1200.0)), SyncPoint(1, TrackPoint(2000, 34.001, 108.0, null))))
    assertEquals(
      json("""{"id":"t1","startedAt":1000,"endedAt":2000,"planned":false,"points":[{"t":1000,"lat":34.0,"lon":108.0,"ele":1200.0,"s":0},{"t":2000,"lat":34.001,"lon":108.0,"s":1}],"name":"鳌太线","datum":"GCJ02","public":true}"""),
      change,
    )
    assertEquals(SYNC_TRACK, bits)
  }

  @Test fun waypointPhotoWaitsForUpload() {
    // New, photo not uploaded yet (no Wi-Fi): everything but the photo, which stays dirty.
    val (change, bits) = waypointChange(wpt.copy(synced = false, photoId = null))
    assertEquals(json("""{"id":"w1","track":"t1","time":1500,"lat":34.0,"lon":108.0,"name":"垭口","description":"","group":""}"""), change)
    assertEquals(SYNC_NAME or SYNC_DESCRIPTION or SYNC_GROUP, bits)
    assertEquals(json("""{"id":"w1","photo":"abc"}""") to SYNC_PHOTO, waypointChange(wpt.copy(dirty = SYNC_PHOTO)))
    // Removed, or kept on this phone only (over quota): the server has none.
    assertEquals(json("""{"id":"w1","photo":""}""") to SYNC_PHOTO, waypointChange(wpt.copy(dirty = SYNC_PHOTO, photo = null, photoId = null)))
    assertEquals(json("""{"id":"w1","photo":""}""") to SYNC_PHOTO, waypointChange(wpt.copy(dirty = SYNC_PHOTO, photoId = "")))
    assertEquals(json("""{"id":"w1","deleted":true}""") to 0, waypointChange(wpt.copy(deleted = true, dirty = SYNC_ALL)))
  }

  @Test fun waypointGroupGoesAsAnAttribute() {
    assertEquals(json("""{"id":"w1","group":"g1"}""") to SYNC_GROUP, waypointChange(wpt.copy(track = null, group = "g1", dirty = SYNC_GROUP)))
    assertEquals(json("""{"id":"w1","group":""}""") to SYNC_GROUP, waypointChange(wpt.copy(track = null, dirty = SYNC_GROUP)))
  }

  @Test fun groupChanges() {
    val g = PendingGroup(1, "g1", synced = true, dirty = 0, edits = 0, name = "水源", deleted = false)
    assertEquals(json("""{"id":"g1","name":"水源"}""") to SYNC_NAME, groupChange(g.copy(synced = false)))
    assertEquals(json("""{"id":"g1"}""") to 0, groupChange(g))
    assertEquals(json("""{"id":"g1","deleted":true}""") to 0, groupChange(g.copy(deleted = true)))
  }

  @Test fun parsesAPull() {
    val page = parseSync(
      """{"cursor":7,"more":true,
        "tracks":[{"id":"t1","startedAt":1,"endedAt":2,"planned":true,"points":[{"t":0,"lat":34,"lon":108,"s":0}],"name":"","datum":"BD09","public":true,"deleted":false}],
        "groups":[{"id":"g1","name":"水源","deleted":false}],
        "waypoints":[{"id":"w1","track":"","group":"g1","time":0,"lat":34,"lon":108,"ele":10.5,"name":"n","description":"d","photo":"","deleted":true}]}""",
    )
    assertEquals(
      SyncPage(
        7, true,
        listOf(SyncTrack("t1", 1, 2, true, listOf(SyncPoint(0, TrackPoint(0, 34.0, 108.0, null))), null, Datum.BD09, true, false)),
        listOf(SyncGroup("g1", "水源", false)),
        listOf(SyncWaypoint("w1", null, "g1", 0, 34.0, 108.0, 10.5, "n", "d", null, true)),
      ),
      page,
    )
  }
}
