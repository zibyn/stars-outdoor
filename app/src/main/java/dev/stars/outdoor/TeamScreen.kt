package dev.stars.outdoor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a 队伍 request failed, from the server's error code; null when not known. */
fun teamReason(code: String?): String? = when (code) {
  "team_not_found" -> "没有这个队伍码"
  "not_initiator" -> "只有发起人可以结束行程"
  "team_ended" -> "行程已结束"
  "unauthorized" -> "登录已失效，重新登录后再来"
  "offline" -> "没有信号"
  else -> null
}

/**
 * What a 求助 that didn't go out says, and whether it's tried again every 15 s (#71): no signal and server hiccups
 * (a 5xx, or an error with no code) may mend; the rest won't, so it says why and stops. client_outdated: UpgradePrompt
 * says 一键求助 needs the upgrade.
 */
fun sosFailure(code: String?): Pair<String, Boolean> = when (code) {
  "offline" -> "没有信号，求助会每 15 秒重试一次" to true
  null, "internal" -> "求助暂时没发出去，每 15 秒自动重试" to true
  else -> "求助没发出去" + (teamReason(code)?.let { "：$it" } ?: "") to false
}

/** ux-v2 §6.1 兜底: 「{action}没成功」, then why, or 再试一次 when that isn't known. */
fun teamMessage(code: String?, action: String): String = action + "没成功，" + (teamReason(code) ?: "再试一次")

/**
 * 队伍抽屉 (ux-v2 §4.4), a 半屏抽屉. Out of a team (or once its trip has ended): 创建队伍 or a code to join, asked
 * before any login (ux-v2 §8 路径 5). In one: 「队伍 4827 · 5 人」 and 看全队, 求助 held 1.5 s ([onSos] null hides it: in
 * 活动状态 the big key has it), teammates 失联 first, 对话 and 分享位置, then 管理.
 */
@Composable
fun TeamDrawer(
  team: Team?,
  nowMs: Long,
  unread: Int,
  /** Where this phone is, for distance and direction; null if unknown. */
  here: TeamPosition?,
  /** A teammate's place on the 队伍轨迹 ([mateAlongText]); null without one. */
  along: ((TeamPosition) -> String)?,
  full: Boolean,
  onFull: (Boolean) -> Unit,
  name: String,
  onName: (String) -> Unit,
  /** Creating or joining in flight, and what went wrong last. */
  busy: Boolean,
  note: String?,
  onCreate: () -> Unit,
  onJoin: (String) -> Unit,
  onSeeAll: () -> Unit,
  onSos: (() -> Unit)?,
  onFocus: (TeamMember) -> Unit,
  onChat: () -> Unit,
  onShare: () -> Unit,
  onManage: () -> Unit,
  onClose: () -> Unit,
) = HalfDrawer(full, onFull, onClose) {
  Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
    if (team != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      BasicText("队伍 ${team.code} · ${team.members.size} 人", Modifier.weight(1f), style = TextStyle(fontSize = 20.sp))
      if (!team.ended) BasicText("看全队", Modifier.heightIn(min = 56.dp).clickable(onClick = onSeeAll).padding(horizontal = 12.dp).wrapContentHeight(), style = TextStyle(color = Green, fontSize = 16.sp))
    }
    if (team != null && !team.ended) {
      onSos?.let {
        HoldKey("求助", "按住 1.5 秒", 1500, AlertRed, Modifier.fillMaxWidth().padding(top = 8.dp), it)
        BasicText("按住发出，队友手机会响铃。只通知队友，不联系救援", Modifier.padding(top = 4.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      }
      for (m in drawerMates(team, nowMs)) MateRow(m, nowMs, here, along) { onFocus(m) }
    }
    if (team != null) {
      if (team.ended) BasicText("行程已结束，位置共享已停止，对话仍保留", Modifier.padding(vertical = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
          Button("对话", primary = false, onChat, Modifier.fillMaxWidth())
          // ux-v2 §4.4: unread is a dot.
          if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(8.dp).background(AlertRed, CircleShape))
        }
        if (!team.ended) Button("分享位置", primary = false, onShare, Modifier.weight(1f))
      }
      Button("管理", primary = false, onManage)
    }
    if (team == null || team.ended) {
      var code by rememberSaveable { mutableStateOf("") }
      var short by remember { mutableStateOf(false) }
      BasicText("和同行的人互相看到位置。发起人创建队伍后把 4 位队伍码告诉队友，队友输入即可加入。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      Field("你在队伍里的称呼（可不填）", name, { onName(it.take(20)) }, KeyboardType.Text)
      Field("队伍码", code, { code = it.filter(Char::isDigit).take(4) }, KeyboardType.NumberPassword)
      Button(if (busy) "正在加入…" else "加入队伍", primary = true, onClick = { short = code.length != 4; if (!busy && !short) onJoin(code) })
      Button("创建队伍", primary = false, onClick = { if (!busy) onCreate() })
      if (short) BasicText("请输入 4 位队伍码", Modifier.padding(top = 12.dp), style = TextStyle(color = AlertRed))
    }
    note?.let { BasicText(it, Modifier.padding(top = 12.dp), style = TextStyle(color = AlertRed)) }
  }
}

/** A teammate's row (ux-v2 §4.4): faded after 5 min, red once 失联, grey once they stopped sharing. Tap: go there. */
@Composable
private fun MateRow(m: TeamMember, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?, onClick: () -> Unit) {
  val at = m.trail.lastOrNull()
  val state = mateState(m, nowMs)
  val (line, color) = when {
    at == null -> "还没有位置" to Color.Gray
    // 失联 leaves out only the 里程 (#97); where they were last still helps find them.
    state == MateState.Lost -> (lostText(at.timeS, nowMs) + " · " + mateDetail(at, here)).removeSuffix(" · ") to AlertRed
    state == MateState.Stopped -> stoppedText(at.timeS) to Color.Gray
    // 沿轨里程 first, fading with the rest after 5 min (#97).
    else -> listOfNotNull(along?.invoke(at), mateDetail(at, here).ifEmpty { null }).joinToString(" · ") to Color.Gray
  }
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (state == MateState.Stale) 0.5f else 1f).clickable(enabled = at != null, onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(28.dp).background(Color(memberColor(m.id)), CircleShape), contentAlignment = Alignment.Center) {
      BasicText(m.name.take(1), style = TextStyle(color = Color.White, fontSize = 14.sp))
    }
    Column(Modifier.padding(start = 12.dp)) {
      BasicText(m.name, style = TextStyle(color = if (state == MateState.Lost) AlertRed else Color.Black, fontSize = 16.sp))
      BasicText(line, style = TextStyle(color = color, fontSize = 12.sp))
    }
  }
}

/** 队友小抽屉 (ux-v2 §4.5): name, how long ago (or 失联 / 停止共享), 沿轨里程 as in the 队伍列表, distance, direction and battery. */
@Composable
fun MateSheet(m: TeamMember, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?, modifier: Modifier) {
  val at = m.trail.lastOrNull() ?: return
  Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) {
    BasicText(m.name, style = TextStyle(fontSize = 18.sp))
    val state = mateState(m, nowMs)
    BasicText(
      when (state) {
        MateState.Lost -> lostText(at.timeS, nowMs)
        MateState.Stopped -> stoppedText(at.timeS)
        else -> agoText(at.timeS, nowMs) + "更新"
      },
      Modifier.padding(top = 4.dp), style = TextStyle(color = if (state == MateState.Lost) AlertRed else Color.Gray),
    )
    // As in the 队伍列表: none once 失联 or 停止共享.
    if (state != MateState.Lost && state != MateState.Stopped) along?.let { BasicText(it(at), Modifier.padding(top = 4.dp)) }
    BasicText(mateDetail(at, here), Modifier.padding(top = 4.dp))
  }
}

/**
 * 队伍管理 (整页, ux-v2 §4.1): 邀请 (the code, to hand out or share), 共享我的位置, 省电模式, 退出队伍 (再点一次,
 * ux-v2 §6.3) and, for the 发起人, 结束行程. [leave] and [end] run off the main thread and throw [OfflineError].
 */
@Composable
fun TeamManageScreen(
  team: Team,
  saver: Boolean,
  onSharing: (Boolean) -> Unit,
  onSaver: () -> Unit,
  leave: () -> Unit,
  end: () -> Unit,
  onLeft: () -> Unit,
  /** 我的轨迹, for the 发起人 to pick the 队伍轨迹 from (§2.11). */
  tracks: List<TrackSummary>,
  /** 指定 or 更换 the 队伍轨迹 (blocking, off the main thread), and 取消 it. */
  giveTrack: (Long) -> Unit,
  dropTrack: () -> Unit,
) {
  var message by rememberSaveable { mutableStateOf<String?>(null) }
  var busy by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  fun call(action: String, block: () -> Unit, done: () -> Unit) {
    if (busy) return
    busy = true
    message = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { done() }.onFailure { message = teamMessage((it as? OfflineError)?.code, action) }
      busy = false
    }
  }
  // 404: already out (left on another phone): just forget it here too.
  fun quit() = call("退出队伍", { runCatching(leave).onFailure { if ((it as? OfflineError)?.code != "team_not_found") throw it } }, onLeft)
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
    BasicText("队伍管理", style = TextStyle(fontSize = 22.sp))
    if (!team.ended) {
      BasicText("队伍码 ${team.code}", Modifier.padding(top = 12.dp), style = TextStyle(fontSize = 28.sp))
      BasicText("把队伍码告诉队友，他们输入后即可加入", style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      Button("邀请", primary = true, onClick = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "在 Stars Outdoor 里输入队伍码 ${team.code} 加入我的队伍")
        context.startActivity(Intent.createChooser(send, "邀请"))
      })
      val me = team.members.firstOrNull { it.id == team.me }
      Switch("共享我的位置", me?.sharing == true) { onSharing(me?.sharing != true) }
      Switch("省电模式（每 2 分钟上报一次）", saver, onSaver)
    }
    TapAgain("退出队伍", "再点一次退出：你会停止共享，也会离开对话", onConfirm = ::quit)
    if (!team.ended && team.initiator == team.me) {
      // §2.11 队伍轨迹: every member takes it as their 参考轨迹; 起算点 changes in its 参考轨迹抽屉 go to them too.
      var picking by rememberSaveable { mutableStateOf(false) }
      val given = team.track
      BasicText("队伍轨迹：" + (given?.name ?: "没有"), Modifier.padding(top = 16.dp), style = TextStyle(fontSize = 16.sp))
      Button(if (given == null) "指定队伍轨迹" else "更换队伍轨迹", primary = false, onClick = { picking = !picking })
      if (given != null) Button("取消队伍轨迹", primary = false, onClick = { call("取消队伍轨迹", dropTrack) {} })
      if (picking) for (t in tracks) BasicText(
        t.name + if (t.planned) "（计划）" else "",
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { call("指定队伍轨迹", { giveTrack(t.id) }) { picking = false } }.wrapContentHeight(),
        style = TextStyle(fontSize = 16.sp),
      )
      TapAgain("结束行程", "再点一次，结束所有人的位置共享") { call("结束行程", end) {} }
    }
    message?.let { BasicText(it, Modifier.padding(top = 12.dp), style = TextStyle(color = Color(0xFFE4572E))) }
  }
}
