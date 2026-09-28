package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Server error code → what the user sees on the team page. */
fun teamMessage(code: String?): String = when (code) {
  "team_not_found" -> "没有这个队伍码，请核对后再试"
  "not_initiator" -> "只有发起人可以结束行程"
  "team_ended" -> "行程已结束"
  "unauthorized" -> "登录已失效，请重新登录"
  "client_outdated" -> "请更新 App 后使用队伍"
  "offline" -> "网络不可用，稍后再试"
  else -> "操作失败，稍后再试"
}

/**
 * 队伍 (§2.11). Not in one: a name, 创建队伍, or a code to join. In one: the code to hand out, the 队伍对话
 * with [unread] messages, the members with how long ago, how far and which way, and their battery; 停止共享,
 * 省电模式, 退出队伍 and, for the 发起人, 结束行程. Once the trip has ended: its 对话 and 退出队伍, and a new
 * team may be made or joined. [create], [join], [leave] and [end] run off the main thread and throw [OfflineError].
 */
@Composable
fun TeamScreen(
  team: Team?,
  nowMs: Long,
  unread: Int,
  onChat: () -> Unit,
  /** Where this phone is, for distance and direction; null if unknown. */
  here: TeamPosition?,
  name: String,
  saver: Boolean,
  onName: (String) -> Unit,
  create: () -> Team,
  join: (String) -> Team,
  onJoined: (Team) -> Unit,
  onSharing: (Boolean) -> Unit,
  onSaver: () -> Unit,
  leave: () -> Unit,
  end: () -> Unit,
  onLeft: () -> Unit,
  onFocus: (TeamPosition) -> Unit,
) {
  var message by rememberSaveable { mutableStateOf<String?>(null) }
  var busy by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  fun <T> call(block: () -> T, done: (T) -> Unit) {
    if (busy) return
    busy = true
    message = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess(done).onFailure { message = teamMessage((it as? OfflineError)?.code) }
      busy = false
    }
  }
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
    BasicText("队伍", style = TextStyle(fontSize = 22.sp))
    if (team != null) Button(if (unread > 0) "队伍对话（$unread 条未读）" else "队伍对话", primary = unread > 0, onClick = onChat)
    if (team != null && team.ended) {
      BasicText("队伍 ${team.code} 的行程已结束，位置共享已停止，对话仍保留", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      Button("退出队伍（同时离开对话）", primary = false, onClick = {
        call({ runCatching(leave).onFailure { if ((it as? OfflineError)?.code != "team_not_found") throw it } }) { onLeft() }
      })
    }
    if (team == null || team.ended) {
      var code by rememberSaveable { mutableStateOf("") }
      BasicText("和同行的人互相看到位置。发起人创建队伍后把 4 位队伍码告诉队友，队友输入即可加入。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      Field("你在队伍里的称呼（可不填）", name, { onName(it.take(20)) }, KeyboardType.Text)
      Button(if (busy) "请稍候…" else "创建队伍", primary = true, onClick = { call(create, onJoined) })
      Field("队伍码", code, { code = it.filter(Char::isDigit).take(4) }, KeyboardType.NumberPassword)
      Button("加入队伍", primary = false, onClick = { if (code.length == 4) call({ join(code) }, onJoined) else message = "请输入 4 位队伍码" })
    } else {
      val me = team.members.firstOrNull { it.id == team.me }
      BasicText("队伍码 ${team.code}", Modifier.padding(top = 12.dp), style = TextStyle(fontSize = 28.sp))
      BasicText("把队伍码告诉队友，他们输入后即可加入", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      for (m in team.members) MemberRow(m, team, nowMs, here, onFocus)
      Switch("共享我的位置", me?.sharing == true) { onSharing(me?.sharing != true) }
      Switch("省电模式（每 2 分钟上报一次）", saver, onSaver)
      Button("退出队伍", primary = false, onClick = {
        // 404: already out (left on another phone): just forget it here too.
        call({ runCatching(leave).onFailure { if ((it as? OfflineError)?.code != "team_not_found") throw it } }) { onLeft() }
      })
      if (team.initiator == team.me) {
        var confirm by rememberSaveable { mutableStateOf(false) }
        Button(if (confirm) "再点一次，结束所有人的位置共享" else "结束行程", primary = confirm, onClick = {
          if (!confirm) confirm = true else call(end) { confirm = false }
        })
      }
    }
    message?.let { BasicText(it, Modifier.padding(top = 12.dp), style = TextStyle(color = Color(0xFFE4572E))) }
  }
}

@Composable
private fun MemberRow(m: TeamMember, team: Team, nowMs: Long, here: TeamPosition?, onFocus: (TeamPosition) -> Unit) {
  val at = m.trail.lastOrNull()
  val status = when {
    m.id == team.me -> if (m.sharing) "共享中" else "已停止共享"
    !m.sharing -> "已停止共享"
    at == null -> "还没有位置"
    else -> listOfNotNull(
      if (presence(at.timeS, nowMs) == Presence.Lost) "失联 · 最后位置 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at.timeS * 1000)) else agoText(at.timeS, nowMs),
      here?.let { distanceText(haversine(TrackPoint(0, it.lat, it.lon, null), TrackPoint(0, at.lat, at.lon, null))) + " " + compass(bearing(it.lat, it.lon, at.lat, at.lon)) },
      at.battery?.let { "电量 $it%" },
    ).joinToString(" · ")
  }
  val tag = listOfNotNull("我".takeIf { m.id == team.me }, "发起人".takeIf { m.id == team.initiator }).joinToString("、")
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = at != null && m.id != team.me) { at?.let(onFocus) },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(28.dp).background(Color(memberColor(m.id)), CircleShape), contentAlignment = Alignment.Center) {
      BasicText(m.name.take(1), style = TextStyle(color = Color.White, fontSize = 14.sp))
    }
    Column(Modifier.padding(start = 12.dp)) {
      BasicText(m.name + if (tag.isEmpty()) "" else "（$tag）", style = TextStyle(fontSize = 16.sp))
      BasicText(status, style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    }
  }
}
