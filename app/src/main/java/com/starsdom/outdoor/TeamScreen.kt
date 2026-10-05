package com.starsdom.outdoor

import androidx.annotation.StringRes
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * 队伍页 out of a team (ux-v3 §8.4 第 1–3 条), a 一级页: the code first, in four big cells with the keyboard up (filled
 * from an invitation on the clipboard, once, never joined by itself); four digits show the 队伍卡片 to 加入, or turn
 * the cells red. Below, 或 建队. Logged out, four digits or 建队 open 登录 over it ([onNeedLogin]); the code it waited
 * for is looked up once logged in. [lookup] runs off the main thread and throws [OfflineError].
 */
@Composable
fun TeamJoinScreen(
  loggedIn: Boolean,
  /** In a team whose trip is on: joining another leaves it (C4-09). */
  inTeam: Boolean,
  lookup: (String) -> TeamCard,
  onNeedLogin: () -> Unit,
  /** 建队 or 加入 in flight (C4-08: the spinner on the button tapped, #149), and what went wrong last with 重试. */
  creating: Boolean,
  joining: Boolean,
  note: String?,
  onRetry: (() -> Unit)?,
  onCreate: () -> Unit,
  onJoin: (String) -> Unit,
  nowMs: Long,
  online: Boolean,
) {
  val context = LocalContext.current
  var code by rememberSaveable { mutableStateOf("") }
  var card by remember { mutableStateOf<TeamCard?>(null) }
  var missing by remember { mutableStateOf(false) }
  var lookupError by remember { mutableStateOf<String?>(null) }
  var tries by remember { mutableIntStateOf(0) }
  // Typed, not filled from the clipboard: only then does a logged-out page open 登录 by itself.
  var typed by remember { mutableStateOf(false) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  // Once, and only with the window focused: Android 10+ hides the clipboard from apps without it.
  val focused = LocalWindowInfo.current.isWindowFocused
  var clipboardRead by remember { mutableStateOf(false) }
  LaunchedEffect(focused) {
    if (!focused || clipboardRead) return@LaunchedEffect
    clipboardRead = true
    if (code.isEmpty()) clipboardCode(context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)?.let { code = it }
  }
  LaunchedEffect(code, loggedIn, tries) {
    card = null
    missing = false
    lookupError = null
    if (code.length < 4) return@LaunchedEffect
    if (!loggedIn) return@LaunchedEffect run { if (typed) onNeedLogin() }
    try {
      card = withContext(Dispatchers.IO) { lookup(code) }
    } catch (e: CancellationException) {
      throw e // the code changed meanwhile
    } catch (e: Exception) {
      // C4-10: wrong or recycled, the cells say so and keep the digits.
      // Nothing joined yet: the 原因 alone (#163: 查码出错 + 重试).
      if (e.errorCode == "team_not_found") missing = true else lookupError = context.getString(reasonOf(e.errorCode))
    }
  }
  Page(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
    Text(stringResource(R.string.team_title), style = MaterialTheme.typography.titleLarge)
    OfflineStatus(online)
    Icon(R.drawable.group_wght500_24px, null, Modifier.padding(top = Space.L).align(Alignment.CenterHorizontally), MaterialTheme.colorScheme.onSurfaceVariant, size = 48.dp)
    Text(stringResource(R.string.team_code_prompt), Modifier.padding(top = Space.XS).align(Alignment.CenterHorizontally), MaterialTheme.colorScheme.onSurfaceVariant)
    CodeCells(code, { code = it.filter(Char::isDigit).take(4); typed = true }, missing, Modifier.padding(top = Space.M).align(Alignment.CenterHorizontally).focusRequester(focus))
    if (missing) Text(stringResource(R.string.reason_no_team), Modifier.padding(top = Space.XS).align(Alignment.CenterHorizontally), MaterialTheme.colorScheme.error)
    lookupError?.let { PageError(it, { tries++ }, Modifier.padding(top = Space.XS)) }
    // Filled from the clipboard while logged out: 加入 asks for the login.
    if (code.length == 4 && !loggedIn) BusyButton(stringResource(R.string.join), primary = true, busy = false, onNeedLogin)
    if (code.length == 4 && loggedIn && card == null && !missing && lookupError == null) Spinner(Modifier.padding(top = Space.M).align(Alignment.CenterHorizontally))
    card?.let { c ->
      Row(Modifier.fillMaxWidth().padding(top = Space.M), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.initiator, c.avatar, 48.dp)
        Column(Modifier.padding(start = Space.M)) {
          Text(stringResource(R.string.team_card_title, c.initiator), style = MaterialTheme.typography.titleMedium)
          Text(cardLine(c, nowMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
      }
      if (inTeam) Text(stringResource(R.string.team_card_leaves), Modifier.padding(top = Space.XS), semantic.warn)
      BusyButton(stringResource(R.string.join), primary = true, joining) { if (!joining && !creating) onJoin(code) }
    }
    Text(stringResource(R.string.or), Modifier.padding(top = Space.L).align(Alignment.CenterHorizontally), MaterialTheme.colorScheme.onSurfaceVariant)
    BusyButton(stringResource(R.string.create_team), primary = false, creating) { if (!joining && !creating) onCreate() }
    note?.let { PageError(it, onRetry, Modifier.padding(top = 12.dp)) }
  }
}

/** Four big digit cells over one field (C4-04), read 「加入码」; red when no team has the code. */
@Composable
private fun CodeCells(code: String, onCode: (String) -> Unit, wrong: Boolean, modifier: Modifier) {
  val label = stringResource(R.string.join_code)
  BasicTextField(
    code, onCode, modifier.semantics { contentDescription = label },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    singleLine = true,
    decorationBox = { inner ->
      Box {
        // The field itself, invisible: it keeps the cursor and the keyboard.
        Box(Modifier.size(1.dp).alpha(0f)) { inner() }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
          for (i in 0 until 4) Box(
            Modifier.size(56.dp, 64.dp).border(
              2.dp,
              when {
                wrong -> MaterialTheme.colorScheme.error
                i == code.length -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
              },
              MaterialTheme.shapes.small,
            ),
            contentAlignment = Alignment.Center,
          ) { Text(code.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")) }
        }
      }
    },
  )
}

/** [Button] that turns into a spinner in place while [busy] (C4-08). */
@Composable
private fun BusyButton(text: String, primary: Boolean, busy: Boolean, onClick: () -> Unit) =
  if (!busy) Button(text, primary, onClick)
  else Box(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).semantics { contentDescription = text }, contentAlignment = Alignment.Center) { Spinner(Modifier.size(24.dp)) }

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
      Button(stringResource(R.string.invite), primary = true, onClick = { invite(context, team.code) })
    }
    Text("成员 ${team.members.size} 人", Modifier.padding(top = 16.dp))
    for (m in team.members) {
      val mine = m.id == team.me
      Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !mine) { open = m.id.takeIf { open != it } },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Avatar(m.name, m.avatar, 28.dp, sharing = m.sharing)
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
      // ponytail: dates only, no numbers; V17 (#187) redoes this pick.
      if (picking) for (t in tracks) TrackRow(t, trackLine(t.startedMs, t.planned, null, System.currentTimeMillis()), reference = false, overlay = null, {
        call(R.string.result_team_track_failed, { giveTrack(t.id) }) { picking = false }
      })
      TapAgain("结束行程", "再点一次，结束所有人的位置共享") { call(R.string.result_end_trip_failed, end) {} }
    }
    if (team.ended) Button("新建或加入队伍", primary = true, onClick = onNewTeam)
    TapAgain("退出队伍", "再点一次退出：你会停止共享，也会离开对话", onConfirm = ::quit)
    message?.let { PageError(it, retry, Modifier.padding(top = 12.dp)) }
  }
}
