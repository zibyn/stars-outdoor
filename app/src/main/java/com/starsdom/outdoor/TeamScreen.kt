package com.starsdom.outdoor

import androidx.annotation.StringRes
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 队伍页 out of a team (ux-v2 §4.4), full screen: 建队 or a code to join, asked before any login (ux-v2 §8 路径 5).
 * Also where a new team starts once the last trip has ended.
 */
@Composable
fun TeamJoinScreen(
  name: String,
  onName: (String) -> Unit,
  /** Creating or joining in flight, and what went wrong last. */
  busy: Boolean,
  note: String?,
  /** 重试 for [note], when it can be. */
  onRetry: (() -> Unit)?,
  onCreate: () -> Unit,
  onJoin: (String) -> Unit,
  online: Boolean,
) {
  var code by rememberSaveable { mutableStateOf("") }
  var short by remember { mutableStateOf(false) }
  Page(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text("队伍", style = MaterialTheme.typography.titleLarge)
    OfflineStatus(online)
    Text("一次出行的群聊：聊天、发位置，互相看到在哪。建队后把 4 位加入码告诉队友，队友输入即可加入。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    Field("你在队伍里的称呼（可不填）", name, { onName(it.take(20)) }, KeyboardType.Text)
    Button("建队", primary = true, onClick = { if (!busy) onCreate() })
    Field("加入码", code, { code = it.filter(Char::isDigit).take(4) }, KeyboardType.NumberPassword)
    Button(if (busy) "正在加入…" else "加入", primary = false, onClick = { short = code.length != 4; if (!busy && !short) onJoin(code) })
    if (short) Text("请输入 4 位加入码", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
    note?.let { PageError(it, onRetry, Modifier.padding(top = 12.dp)) }
  }
}

/** A member's last report (ux-v2 §4.5): how long ago (or 停止共享), 沿轨里程, distance, direction and battery. */
@Composable
private fun MateLines(m: TeamMember, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?) {
  val at = m.trail.lastOrNull() ?: return Text("还没有位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(if (m.sharing) agoText(at.timeS, nowMs) + "更新" else stoppedText(at.timeS), color = MaterialTheme.colorScheme.onSurfaceVariant)
  if (m.sharing) along?.let { Text(it(at), Modifier.padding(top = 4.dp)) }
  mateDetail(at, here).takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(top = 4.dp)) }
}

/** 队友小抽屉 (ux-v2 §4.5), from a dot on the map. */
@Composable
fun MateSheet(m: TeamMember, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?, modifier: Modifier) {
  Sheet(modifier) {
    Text(m.name, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
    MateLines(m, nowMs, here, along)
  }
}

/**
 * 队伍信息 (整页, ux-v2 §4.4), from the 群聊's top bar: 成员 (tap one for their last report), 邀请, 队伍轨迹 and
 * 结束行程 for the 发起人, 停止 / 继续共享, 省电模式, 退出队伍 (再点一次, ux-v2 §6.3). After 结束行程, [onNewTeam]
 * goes to 建队 / 加入. [leave], [end], [giveTrack] and [dropTrack] run off the main thread and throw [OfflineError].
 */
@Composable
fun TeamInfoScreen(
  team: Team,
  nowMs: Long,
  /** Where this phone is, for distance and direction; null if unknown. */
  here: TeamPosition?,
  /** A teammate's place on the 队伍轨迹 ([mateAlongText]); null without one. */
  along: ((TeamPosition) -> String)?,
  saver: Boolean,
  onSharing: (Boolean) -> Unit,
  onSaver: () -> Unit,
  leave: () -> Unit,
  end: () -> Unit,
  onLeft: () -> Unit,
  onNewTeam: () -> Unit,
  /** 我的轨迹, for the 发起人 to pick the 队伍轨迹 from (§2.11). */
  tracks: List<TrackSummary>,
  /** 绑定 or 更换 the 队伍轨迹 (blocking), and 取消 it. */
  giveTrack: (Long) -> Unit,
  dropTrack: () -> Unit,
  onBack: () -> Unit,
  online: Boolean,
) {
  var message by rememberSaveable { mutableStateOf<String?>(null) }
  // What failed, run again by 重试 (not kept across recreation: the message goes with it).
  var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
  var busy by rememberSaveable { mutableStateOf(false) }
  var open by rememberSaveable { mutableStateOf<Long?>(null) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  fun call(@StringRes failed: Int, block: () -> Unit, done: () -> Unit) {
    if (busy) return
    busy = true
    message = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { done() }.onFailure {
        message = context.errorText(failed, it.errorCode)
        retry = { call(failed, block, done) }
      }
      busy = false
    }
  }
  // 404: already out (left on another phone): just forget it here too.
  fun quit() = call(R.string.result_leave_team_failed, { runCatching(leave).onFailure { if (it.errorCode != "team_not_found") throw it } }, onLeft)
  Page(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("返回", Modifier.heightIn(min = 56.dp).clickable(onClick = onBack).padding(end = 16.dp).wrapContentHeight(), color = MaterialTheme.colorScheme.primary)
      Text("队伍信息", style = MaterialTheme.typography.titleLarge)
    }
    OfflineStatus(online)
    if (team.ended) Text("行程已结束，位置共享已停止，对话仍保留", Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    else {
      Text("加入码 ${team.code}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineSmall)
      Button("邀请", primary = true, onClick = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "在星径里输入加入码 ${team.code} 加入我的队伍")
        context.startActivity(Intent.createChooser(send, "邀请"))
      })
    }
    Text("成员 ${team.members.size} 人", Modifier.padding(top = 16.dp))
    for (m in team.members) {
      val mine = m.id == team.me
      Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !mine) { open = m.id.takeIf { open != it } },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(28.dp).then(if (m.sharing) Modifier.background(semantic.teammate, CircleShape) else Modifier.border(3.dp, MaterialTheme.colorScheme.outline, CircleShape)), contentAlignment = Alignment.Center) {
          Text(m.name.take(1), color = if (m.sharing) semantic.stroke else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
          m.name + listOfNotNull("我".takeIf { mine }, "发起人".takeIf { m.id == team.initiator }).joinToString("") { " · $it" },
          Modifier.padding(start = 12.dp),
        )
      }
      if (open == m.id) Column(Modifier.padding(start = 40.dp, bottom = 8.dp)) { MateLines(m, nowMs, here, along) }
    }
    if (!team.ended) {
      val sharing = team.members.firstOrNull { it.id == team.me }?.sharing == true
      Button(if (sharing) "停止共享我的位置" else "继续共享我的位置", primary = false, onClick = { onSharing(!sharing) })
      Switch("省电模式（每 2 分钟上报一次）", saver, onSaver)
    }
    if (!team.ended && team.initiator == team.me) {
      // §2.11 队伍轨迹: every member takes it as their 参考轨迹; 起算点 changes in its 参考轨迹抽屉 go to them too.
      var picking by rememberSaveable { mutableStateOf(false) }
      val given = team.track
      Text("队伍轨迹：" + (given?.name ?: "没有"), Modifier.padding(top = 16.dp))
      Button(if (given == null) "绑定队伍轨迹" else "更换队伍轨迹", primary = false, onClick = { picking = !picking })
      if (given != null) Button("取消队伍轨迹", primary = false, onClick = { call(R.string.result_team_track_failed, dropTrack) {} })
      if (picking) for (t in tracks) Text(
        t.name + if (t.planned) "（计划）" else "",
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { call(R.string.result_team_track_failed, { giveTrack(t.id) }) { picking = false } }.wrapContentHeight(),
      )
      TapAgain("结束行程", "再点一次，结束所有人的位置共享") { call(R.string.result_end_trip_failed, end) {} }
    }
    if (team.ended) Button("新建或加入队伍", primary = true, onClick = onNewTeam)
    TapAgain("退出队伍", "再点一次退出：你会停止共享，也会离开对话", onConfirm = ::quit)
    message?.let { PageError(it, retry, Modifier.padding(top = 12.dp)) }
  }
}
