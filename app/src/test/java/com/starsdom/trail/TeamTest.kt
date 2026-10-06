package com.starsdom.trail

import com.starsdom.trail.track.TrackPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

  // #184: a rename comes as the team with the new name; messages already here show it too, a left member's keep theirs.
  @Test fun renamedMemberRenamesTheirOlderMessages() {
    fun msg(seq: Long, from: Long?, name: String) = TeamMessage(seq, from, name, 0, "text", "x")
    val have = Team(7, "4827", 1, me = 1, ended = false, cursor = 3, members = listOf(member(1), member(2)),
      messages = listOf(msg(1, 2, "岩羊27"), msg(2, 3, "走了的人"), msg(3, null, "已注销用户")))
    val renamed = Team(7, "4827", 1, me = 1, ended = false, cursor = 3, members = listOf(member(1), member(2).copy(name = "老王")))
    assertEquals(listOf("老王", "走了的人", "已注销用户"), mergeTeam(have, renamed).messages.map { it.name })
  }

  @Test fun parsesTheServersTeam() {
    val json = """{"id":7,"code":"0482","initiator":1,"me":2,"ended":false,"cursor":12,"messages":[],"members":[
      {"id":1,"name":"尾号8000","sharing":true,"positions":[{"time":100,"lat":34.5,"lon":108.25,"battery":80}]},
      {"id":2,"name":"老王","avatar":"0123456789abcdef0123456789abcdef","sharing":false,"positions":[]}]}"""
    val t = parseTeam(json)
    assertEquals(Team(7, "0482", 1, me = 2, ended = false, cursor = 12, members = listOf(
      TeamMember(1, "尾号8000", true, listOf(TeamPosition(100, 34.5, 108.25, 80))),
      TeamMember(2, "老王", false, emptyList(), avatar = "0123456789abcdef0123456789abcdef"),
    )), t)
    assertEquals("""{"positions":[{"time":100,"lat":34.5,"lon":108.25,"battery":80},{"time":130,"lat":34.0,"lon":108.0}]}""",
      positionsJson(listOf(TeamPosition(100, 34.5, 108.25, 80), TeamPosition(130, 34.0, 108.0, null))))
  }

  @Test fun aTeammatesLastReportSaysHowLongAgo() {
    val now = 10_000_000L
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

  // C4-47: 距离, 方向, 电量; what's unknown is 「—」.
  @Test fun mateValues() {
    val here = at(0)
    assertEquals(Triple("1.11 km", "北", "18%"), mateValues(at(0, 1_111.95, battery = 18), here))
    assertEquals(Triple("1.11 km", "北", "—"), mateValues(at(0, 1_111.95), here))
    assertEquals(Triple("—", "—", "18%"), mateValues(at(0, 1_111.95, battery = 18), null))
  }

  // C4-45, C4-46.
  @Test fun whenAMateWasLastHeardOf() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    val now = 1_791_000_000_000L
    val last = TeamPosition(now / 1000 - 180, 34.0, 108.0, null)
    assertEquals("3 分钟前更新", updatedText(TeamMember(2, "老王", true, listOf(last)), now))
    assertEquals("刚刚更新", updatedText(TeamMember(2, "老王", true, listOf(last.copy(timeS = now / 1000 - 5))), now))
    val stopped = SimpleDateFormat("H:mm", Locale.CHINA).format(Date(last.timeS * 1000)) + " 停止共享"
    assertEquals(stopped, updatedText(TeamMember(2, "老王", false, listOf(last)), now))
  }

  // C4-29: today 「小李 · 7:52」, before 「小李 · 10月5日 7:52」.
  @Test fun senderLines() {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    val now = Calendar.getInstance().apply { set(2026, 9, 6, 12, 0) }.timeInMillis
    val today = Calendar.getInstance().apply { set(2026, 9, 6, 7, 52) }.timeInMillis / 1000
    val before = Calendar.getInstance().apply { set(2026, 9, 5, 7, 52) }.timeInMillis / 1000
    assertEquals("小李 · 7:52", senderLine("小李", today, now))
    assertEquals("小李 · 10月5日 7:52", senderLine("小李", before, now))
  }

  @Test fun sharedPositionsMakeATrackBrokenWhereSharingStopped() {
    val lines = listOf(
      tripLine(at(0)), tripLine(at(30, 60.0)), tripLine(at(60, 120.0)),
      TRIP_BREAK, // 停止共享
      tripLine(at(660, 200.0)), tripLine(at(690, 260.0)),
      // Nothing for 11 min (no fix, or the phone off): still one segment.
      tripLine(at(1350, 300.0)),
      "garbage",
    )
    val segments = tripSegments(lines)
    assertEquals(listOf(3, 3), segments.map { it.size })
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

  @Test fun aTeammatesPlaceOnTheTeamTrack() {
    // C4-48, C4-49.
    assertEquals("沿轨 7.3 km · 领先 0.8 km", mateAlongText(listOf(7_300.0), listOf(6_500.0)))
    assertEquals("沿轨 5.2 km · 落后 1.3 km", mateAlongText(listOf(5_200.0), listOf(6_500.0)))
    // Side by side: no 领先 / 落后 to speak of.
    assertEquals("沿轨 6.5 km", mateAlongText(listOf(6_520.0), listOf(6_500.0)))
    // Several values, theirs or mine, or me off it / no fix: just theirs.
    assertEquals("沿轨 3.1 / 13.7 km", mateAlongText(listOf(3_100.0, 13_700.0), listOf(6_500.0)))
    assertEquals("沿轨 7.3 km", mateAlongText(listOf(7_300.0), listOf(3_100.0, 13_700.0)))
    assertEquals("沿轨 7.3 km", mateAlongText(listOf(7_300.0), emptyList()))
    assertEquals("沿轨 7.3 km", mateAlongText(listOf(7_300.0), null))
    assertEquals("不在轨迹上", mateAlongText(emptyList(), listOf(6_500.0)))
  }

  @Test fun aLocationCarriesTheSendersPlaceOnTheTeamTrack() {
    assertEquals("""{"kind":"location","lat":34.0,"lon":108.0,"along":[3100.0,13700.0]}""", messageJson("location", lat = 34.0, lon = 108.0, along = listOf(3_100.0, 13_700.0)))
    // No 队伍轨迹: nothing sent.
    assertEquals("""{"kind":"location","lat":34.0,"lon":108.0}""", messageJson("location", lat = 34.0, lon = 108.0))
    val back = parseMessage(Json.parseToJsonElement("""{"seq":1,"name":"老王","time":0,"kind":"location","lat":34.0,"lon":108.0,"along":[7300]}""").jsonObject)
    assertEquals(listOf(7_300.0), back.along)
    // C4-32: how far from me; 「—」 without a fix; mine just 「📍 位置」.
    assertEquals("📍 位置 · 1.20 km", locationLine(1_200.0, mine = false))
    assertEquals("📍 位置 · —", locationLine(null, mine = false))
    assertEquals("📍 位置", locationLine(null, mine = true))
  }

  // #186: the clipboard fills the code only from an invitation (C4-53), never from any four digits.
  @Test fun codeFromTheClipboard() {
    assertEquals("4827", clipboardCode("加入我的队伍：在星径里输入加入码 4827\n还没装星径？下载：https://example.com"))
    assertEquals("0482", clipboardCode("加入码0482"))
    for (s in listOf(null, "", "4827", "加入码 482", "加入码 48271", "电话 1234")) assertEquals(s, null, clipboardCode(s))
  }

  @Test fun theInvitationNamesTheCodeAndWhereToGetTheApp() {
    assertEquals("加入我的队伍：在星径里输入加入码 4827\n还没装星径？下载：$DOWNLOAD_URL", inviteText("4827"))
    assertEquals("4827", clipboardCode(inviteText("4827")))
  }

  // C4-07.
  @Test fun parsesTheTeamCard() {
    val c = parseTeamCard("""{"id":7,"initiator":"老王","initiatorAvatar":"ab","members":3,"createdAt":1000}""")
    assertEquals(TeamCard(7, "老王", "ab", 3, 1000), c)
    assertEquals("3 人 · 25 分钟前建", cardLine(c, (1000 + 25 * 60) * 1000L))
    assertEquals(null, parseTeamCard("""{"id":7,"initiator":"已注销用户","members":1,"createdAt":1000}""").avatar)
  }
}
