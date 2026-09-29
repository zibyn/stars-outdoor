package dev.stars.outdoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamTest {
  // 0.0001° of latitude is about 11 m.
  private fun at(timeS: Long, northM: Double = 0.0, battery: Int? = null) = TeamPosition(timeS, 34.0 + northM / 111_195.0, 108.0, battery)

  @Test fun movingReportsEvery30sOr50mWhicheverFirst() {
    val last = at(0)
    assertTrue("first fix", shouldReport(null, at(0), saver = false))
    assertTrue("50 m before 30 s", shouldReport(last, at(10, 55.0), saver = false))
    assertFalse("walking, 20 s", shouldReport(last, at(20, 30.0), saver = false))
    assertTrue("walking, 30 s", shouldReport(last, at(30, 30.0), saver = false))
  }

  @Test fun standingStillSendsAHeartbeatEvery3Min() {
    val last = at(0)
    assertFalse("GPS jitter is not moving", shouldReport(last, at(60, 8.0), saver = false))
    assertFalse(shouldReport(last, at(179, 8.0), saver = false))
    assertTrue(shouldReport(last, at(180, 8.0), saver = false))
  }

  @Test fun lowBatteryAndSaverReportOnAFixedInterval() {
    val last = at(0)
    assertFalse("< 20%: 2 min, however far", shouldReport(last, at(119, 500.0, battery = 19), saver = false))
    assertTrue(shouldReport(last, at(120, 0.0, battery = 19), saver = false))
    assertFalse("< 10%: 5 min", shouldReport(last, at(299, 500.0, battery = 9), saver = false))
    assertTrue(shouldReport(last, at(300, 0.0, battery = 9), saver = false))
    assertFalse("省电模式: 2 min", shouldReport(last, at(119, 500.0, battery = 90), saver = true))
    assertTrue(shouldReport(last, at(120, 0.0, battery = 90), saver = true))
  }

  @Test fun backOnlineSendsTheLatestThenTheTrailThinnedTo2Min() {
    assertEquals(emptyList<TeamPosition>(), uploadOrder(emptyList()))
    assertEquals(listOf(at(0)), uploadOrder(listOf(at(0))))
    // 10 minutes without signal, a report every 30 s.
    val sent = uploadOrder((0L..20L).map { at(it * 30) })
    assertEquals(listOf(600L, 0L, 120L, 240L, 360L, 480L), sent.map { it.timeS })
  }

  @Test fun daysOfflineStillFitOneRequest() {
    val sent = uploadOrder((0L..10_000L).map { at(it * 30) })
    assertEquals(1000, sent.size)
    assertEquals(300_000L, sent[0].timeS)
    assertEquals(299_880L, sent.last().timeS)
  }

  private fun member(id: Long, vararg times: Long, sharing: Boolean = true) = TeamMember(id, "队员$id", sharing, times.map { at(it) })

  @Test fun liveMessagesAppendToTrailsAndReplaceTheRest() {
    val have = Team(7, "4827", 1, me = 1, ended = false, cursor = 5, members = listOf(member(1, 10, 20), member(2, 10)))
    // Member 2 stopped sharing, 3 joined, a backfilled point (15) arrives after a newer one, 20 repeats.
    val msg = Team(7, "4827", 1, me = 1, ended = false, cursor = 9, members = listOf(member(1, 20, 15), member(2, sharing = false), member(3, 30)))
    val merged = mergeTeam(have, msg)
    assertEquals(9L, merged.cursor)
    assertEquals(listOf(10L, 15L, 20L), merged.members[0].trail.map { it.timeS })
    assertEquals(listOf(10L), merged.members[1].trail.map { it.timeS })
    assertFalse(merged.members[1].sharing)
    assertEquals(listOf(30L), merged.members[2].trail.map { it.timeS })
    // A member who left is gone, trail and all.
    assertEquals(listOf(1L, 3L), mergeTeam(merged, msg.copy(members = listOf(member(1), member(3)))).members.map { it.id })
    // Another team (joined elsewhere) replaces it outright; so does the first message.
    assertEquals(listOf(15L, 20L), mergeTeam(have.copy(id = 6), msg).members[0].trail.map { it.timeS })
    assertEquals(listOf(15L, 20L), mergeTeam(null, msg).members[0].trail.map { it.timeS })
  }

  @Test fun parsesTheServersTeam() {
    val json = """{"id":7,"code":"0482","initiator":1,"me":2,"ended":false,"cursor":12,"messages":[],"members":[
      {"id":1,"name":"尾号8000","sharing":true,"positions":[{"time":100,"lat":34.5,"lon":108.25,"battery":80}]},
      {"id":2,"name":"老王","sharing":false,"positions":[]}]}"""
    val t = parseTeam(json)
    assertEquals(Team(7, "0482", 1, me = 2, ended = false, cursor = 12, members = listOf(
      TeamMember(1, "尾号8000", true, listOf(TeamPosition(100, 34.5, 108.25, 80))),
      TeamMember(2, "老王", false, emptyList()),
    )), t)
    assertEquals("""{"positions":[{"time":100,"lat":34.5,"lon":108.25,"battery":80},{"time":130,"lat":34.0,"lon":108.0}]}""",
      positionsJson(listOf(TeamPosition(100, 34.5, 108.25, 80), TeamPosition(130, 34.0, 108.0, null))))
  }

  @Test fun teammateFadesAfter5MinAndIsOutOfContactAfter30() {
    val now = 10_000_000L
    assertEquals(Presence.Fresh, presence(now / 1000 - 299, now))
    assertEquals(Presence.Stale, presence(now / 1000 - 301, now))
    assertEquals(Presence.Stale, presence(now / 1000 - 1800, now))
    assertEquals(Presence.Lost, presence(now / 1000 - 1801, now))
    assertEquals("刚刚", agoText(now / 1000 - 30, now))
    assertEquals("4 分钟前", agoText(now / 1000 - 299, now))
    assertEquals("2 小时前", agoText(now / 1000 - 2 * 3600 - 60, now))
  }

  @Test fun directionToATeammate() {
    assertEquals("北", compass(bearing(34.0, 108.0, 34.01, 108.0)))
    assertEquals("东", compass(bearing(34.0, 108.0, 34.0, 108.01)))
    assertEquals("西南", compass(bearing(34.0, 108.0, 33.99, 107.99)))
    assertEquals("北", compass(bearing(34.0, 108.0, 34.01, 107.9999)))
  }

  @Test fun teamButtonShowsWhoIsOnlineOrOutOfContact() {
    val now = 10_000_000L
    fun m(id: Long, agoS: Long, sharing: Boolean = true) = TeamMember(id, "m$id", sharing, listOf(TeamPosition(now / 1000 - agoS, 34.0, 108.0, null)))
    fun team(vararg members: TeamMember, ended: Boolean = false) = Team(1, "4827", 1, 1, ended, 0, members.toList())
    assertEquals("队伍" to false, teamButton(null, now))
    assertEquals("队伍 3" to false, teamButton(team(m(1, 10), m(2, 400), m(3, 10), m(4, 10, sharing = false)), now))
    assertEquals("2 人失联" to true, teamButton(team(m(1, 10), m(2, 2000), m(3, 4000), m(4, 4000, sharing = false)), now))
    assertEquals("我自己失联不算", "队伍 1" to false, teamButton(team(m(1, 4000), m(2, 10)), now))
    assertEquals("队伍" to false, teamButton(team(m(1, 10), m(2, 4000), ended = true), now))
  }

  @Test fun drawerListsTeammatesLostFirstWithoutMe() {
    val now = 10_000_000L
    fun m(id: Long, lastS: Long?, sharing: Boolean = true) = TeamMember(id, "m$id", sharing, listOfNotNull(lastS?.let { at(it) }))
    val t = Team(1, "4827", 1, me = 1, ended = false, cursor = 0, members = listOf(
      m(1, 9_990), m(2, 9_990), m(3, 9_990 - 3_600), m(4, 9_000, sharing = false), m(5, null), m(6, 9_990 - 600),
    ))
    assertEquals(listOf(3L, 2L, 4L, 5L, 6L), drawerMates(t, now).map { it.id })
    assertEquals(MateState.Fresh, mateState(t.members[1], now))
    assertEquals(MateState.Lost, mateState(t.members[2], now))
    assertEquals(MateState.Stopped, mateState(t.members[3], now))
    assertEquals(MateState.Stale, mateState(t.members[5], now))
    assertEquals(null, mateState(t.members[4], now))
  }

  @Test fun rowAndLabelTexts() {
    assertEquals("失联 12 分钟", lostText(0, 12 * 60_000L + 30_000))
    assertEquals("失联 2 小时", lostText(0, 125 * 60_000L))
    val here = at(0)
    assertEquals("1.11 km · 北 · 电量 18%", mateDetail(at(0, 1_111.95, battery = 18), here))
    assertEquals("1.11 km · 北", mateDetail(at(0, 1_111.95), here))
    assertEquals("电量 18%", mateDetail(at(0, 1_111.95, battery = 18), null))
  }

  @Test fun failuresSayWhatDidntWorkThenWhy() {
    assertEquals("加入队伍没成功，没有这个队伍码", teamMessage("team_not_found", "加入队伍"))
    assertEquals("退出队伍没成功，没有信号", teamMessage("offline", "退出队伍"))
    assertEquals("结束行程没成功，再试一次", teamMessage(null, "结束行程"))
  }

  @Test fun sharedPositionsMakeATrackBrokenWhereSharingStoppedOrWentQuiet() {
    val lines = listOf(
      tripLine(at(0)), tripLine(at(30, 60.0)), tripLine(at(60, 120.0)),
      TRIP_BREAK, // 停止共享
      tripLine(at(660, 200.0)), tripLine(at(690, 260.0)),
      // Nothing for 11 min (no fix, or the phone off): a gap too.
      tripLine(at(1350, 300.0)),
      "garbage",
    )
    val segments = tripSegments(lines)
    assertEquals(listOf(3, 2, 1), segments.map { it.size })
    assertEquals(30_000L, segments[0][1].timeMs)
    assertEquals(34.0 + 60 / 111_195.0, segments[0][1].lat, 1e-9)
    assertTrue(tripSegments(emptyList()).isEmpty())
    assertTrue(tripSegments(listOf(TRIP_BREAK)).isEmpty())
  }

  @Test fun theTeamTrackRefAndItsPointsGoBothWays() {
    val json = """{"id":7,"code":"0482","initiator":1,"me":2,"ended":false,"cursor":12,"messages":[],"members":[],
      "track":{"version":3,"uuid":"u1","name":"武功山环线","reversed":true,"start":1200.5}}"""
    assertEquals(TeamTrackRef(3, "u1", "武功山环线", TrackStart(reversed = true, startM = 1200.5)), parseTeam(json).track)
    val segments = listOf(listOf(TrackPoint(1000, 34.0, 108.0, 1200.0), TrackPoint(2000, 34.01, 108.0, null)), listOf(TrackPoint(3000, 34.02, 108.0, null)))
    val sent = teamTrackJson("u1", "武功山环线", TrackStart(reversed = true), segments)
    assertEquals("""{"uuid":"u1","name":"武功山环线","reversed":true,"start":0.0,"points":[{"t":1000,"lat":34.0,"lon":108.0,"ele":1200.0,"s":0},""" +
      """{"t":2000,"lat":34.01,"lon":108.0,"s":0},{"t":3000,"lat":34.02,"lon":108.0,"s":1}]}""", sent)
    // What the server sends back is that with its version.
    val got = parseTeamTrack(sent.replaceFirst("{", """{"version":3,"""))
    assertEquals(TeamTrackRef(3, "u1", "武功山环线", TrackStart(reversed = true)), got.first)
    assertEquals(segments, got.second)
  }

  @Test fun aNewTeamTrackIsFollowedUnlessIveMovedOn() {
    // The first one this trip is taken, whatever I had.
    assertTrue(followTeamTrack(reference = 5, lastTeamTrack = null))
    // 更换: taken while I'm still on the last one…
    assertTrue(followTeamTrack(reference = 9, lastTeamTrack = 9))
    // …not once I chose another, or none.
    assertFalse(followTeamTrack(reference = 5, lastTeamTrack = 9))
    assertFalse(followTeamTrack(reference = null, lastTeamTrack = 9))
  }

  @Test fun systemMessagesReadAsTheirText() {
    assertEquals("发起人把队伍轨迹换成 武功山环线（反向）", TeamMessage(1, 1, "a", 0, "system", text = "发起人把队伍轨迹换成 武功山环线（反向）").summary())
  }

  @Test fun myCopyOfATeamTrackHasOneSyncIdOnEveryPhone() {
    val id = teamTrackCopyUuid("0123456789abcdef0123456789abcdef")
    assertTrue(Regex("^[0-9a-f]{32}$").matches(id))
    assertEquals(id, teamTrackCopyUuid("0123456789abcdef0123456789abcdef"))
    assertFalse(id == teamTrackCopyUuid("fedcba9876543210fedcba9876543210"))
    assertEquals(TeamTrackHere(7, 42, 3), TeamTrackHere.parse(TeamTrackHere(7, 42, 3).text))
    assertEquals(null, TeamTrackHere.parse("7:42"))
  }
}
