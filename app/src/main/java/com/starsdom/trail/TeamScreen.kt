package com.starsdom.trail

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.starsdom.trail.track.TrackSummary
import kotlin.coroutines.cancellation.CancellationException
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

/** A member's last report (C4-45…50): when, then 距离 · 方向 · 电量, and 沿轨 when there's a 队伍轨迹. */
@Composable
private fun MateLines(m: TeamMember, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?) {
  val at = m.trail.lastOrNull() ?: return Text(stringResource(R.string.team_no_position), color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(updatedText(m, nowMs).orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
  val (distance, direction, battery) = mateValues(at, here)
  Row(Modifier.fillMaxWidth().padding(top = Space.XS)) {
    for ((label, value) in listOf(R.string.mate_distance to distance, R.string.mate_direction to direction, R.string.mate_battery to battery)) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
      }
    }
  }
  if (m.sharing) along?.let { Text(it(at), Modifier.padding(top = Space.XS)) }
}

/** A member's name, 「· 发起人」 after the 发起人's (C4-44, C4-56). */
@Composable
private fun memberName(m: TeamMember, team: Team) = m.name + if (m.id == team.initiator) stringResource(R.string.team_initiator_mark) else ""

/** 队友小抽屉 (§8.4 第 17 条), from a dot on the map: who and where, nothing to do. */
@Composable
fun MateSheet(m: TeamMember, team: Team, nowMs: Long, here: TeamPosition?, along: ((TeamPosition) -> String)?, modifier: Modifier) {
  Sheet(modifier) {
    Row(Modifier.padding(bottom = Space.XS), verticalAlignment = Alignment.CenterVertically) {
      Avatar(m.name, m.avatar, 40.dp, sharing = m.sharing)
      Text(memberName(m, team), Modifier.padding(start = Space.M), style = MaterialTheme.typography.titleLarge)
    }
    MateLines(m, nowMs, here, along)
  }
}

/**
 * 队伍信息 (二级页, §8.4 第 18–22 条): 成员, me first (tap a teammate for their last report, their 尾迹 bold on the map);
 * 我的位置 (共享, 省电); 队伍轨迹 (the 发起人 picks one from 我的轨迹); then, in a red frame, 结束行程 (发起人) and
 * 退出队伍, each 再点一次. [leave], [end], [giveTrack] and [dropTrack] run off the main thread and throw [OfflineError].
 */
@Composable
fun TeamInfoScreen(
  team: Team,
  nowMs: Long,
  /** Where this phone is, for distance and direction; null if unknown. */
  here: TeamPosition?,
  /** A teammate's place on the 队伍轨迹 ([mateAlongText]); null without one. */
  along: ((TeamPosition) -> String)?,
  /** The teammate whose 尾迹 is bold, and picking one (null: none). */
  highlighted: Long?,
  onHighlight: (Long?) -> Unit,
  saver: Boolean,
  onSharing: (Boolean) -> Unit,
  onSaver: () -> Unit,
  leave: () -> Unit,
  end: () -> Unit,
  onLeft: () -> Unit,
  onEnded: () -> Unit,
  /** 我的轨迹, for the 发起人 to pick the 队伍轨迹 from (§2.11). */
  tracks: List<TrackSummary>,
  /** Makes a track the 队伍轨迹 (blocking), giving back the one it replaced here, for [onTrackGiven]'s 撤销. */
  giveTrack: (Long) -> Long?,
  onTrackGiven: (before: Long?) -> Unit,
  dropTrack: () -> Unit,
  onBack: () -> Unit,
  online: Boolean,
  reconnecting: Boolean,
) {
  var message by rememberSaveable { mutableStateOf<String?>(null) }
  // What failed, run again by 重试 (not kept across recreation: the message goes with it).
  var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
  var busy by rememberSaveable { mutableStateOf(false) }
  var picking by rememberSaveable { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  fun <T> call(@StringRes failed: Int, block: () -> T, done: (T) -> Unit) {
    if (busy) return
    busy = true
    message = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess(done).onFailure {
        message = context.errorText(failed, it.errorCode)
        retry = { call(failed, block, done) }
      }
      busy = false
    }
  }
  // 404: already out (left on another phone): just forget it here too.
  fun quit() = call(R.string.result_leave_team_failed, { runCatching(leave).onFailure { if (it.errorCode != "team_not_found") throw it } }) { onLeft() }
  val me = team.members.firstOrNull { it.id == team.me }
  val sharing = me?.sharing == true
  val initiator = team.initiator == team.me
  Box(Modifier.fillMaxSize()) {
    Page(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
      // C4-51: 「← 队伍信息」.
      Row(verticalAlignment = Alignment.CenterVertically) {
        DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onBack)
        Text(stringResource(R.string.team_info), Modifier.padding(start = Space.XS), style = MaterialTheme.typography.titleLarge)
      }
      TeamStatus(online, reconnecting)
      Section(stringResource(R.string.team_members_title, team.members.size))
      for (m in listOfNotNull(me) + team.members.filter { it.id != team.me }) {
        val mine = m.id == team.me
        Row(
          Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !mine) { onHighlight(m.id.takeIf { highlighted != it }) },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Avatar(m.name, m.avatar, 28.dp, sharing = m.sharing)
          Text(memberName(m, team), Modifier.padding(start = 12.dp))
        }
        if (highlighted == m.id) Column(Modifier.padding(start = 40.dp, bottom = 8.dp)) { MateLines(m, nowMs, here, along) }
      }
      if (!team.ended) {
        Section(stringResource(R.string.my_position))
        Switch(stringResource(R.string.share_position), sharing) { onSharing(!sharing) }
        // Off while not sharing: there's nothing to save then.
        Switch(stringResource(R.string.saver), saver, enabled = sharing, onClick = onSaver)
        Text(stringResource(R.string.saver_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        // C4-61…63: the 发起人 picks, changes or removes it; the rest see which.
        Section(stringResource(R.string.team_track_title))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(team.track?.name ?: stringResource(R.string.team_track_none), Modifier.weight(1f))
          if (initiator) {
            if (team.track != null) TextAction(stringResource(R.string.team_track_remove)) { call(R.string.result_team_track_failed, dropTrack) {} }
            TextAction(stringResource(if (team.track == null) R.string.team_track_pick else R.string.team_track_change)) { picking = true }
          }
        }
      }
      message?.let { PageError(it, retry, Modifier.padding(top = 12.dp)) }
      // §8.4 第 18 条: the dangerous ones last, in a red frame.
      Column(Modifier.padding(vertical = Space.L).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium).padding(Space.M)) {
        if (!team.ended && initiator) {
          Text(stringResource(R.string.end_trip_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
          TapAgain(stringResource(R.string.end_trip), stringResource(R.string.end_trip_armed)) { call(R.string.result_end_trip_failed, end) { onEnded() } }
        }
        Text(stringResource(R.string.leave_team_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        TapAgain(stringResource(R.string.leave_team), stringResource(R.string.leave_team_armed), onConfirm = ::quit)
      }
    }
    // C4-64: 我的轨迹 to pick from, newest first as in its drawer.
    if (picking) {
      BackHandler { picking = false }
      Sheet(Modifier.align(Alignment.BottomCenter)) {
        Text(stringResource(R.string.team_track_sheet), Modifier.padding(bottom = Space.XS), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
          items(tracks, key = { it.id }) { t ->
            TrackRow(t, trackLine(t.startedMs, t.planned, null, nowMs), reference = false, overlay = null, {
              call(R.string.result_team_track_failed, { giveTrack(t.id) }) { before -> picking = false; onTrackGiven(before) }
            })
          }
        }
      }
    }
  }
}

@Composable
private fun Section(title: String) =
  Text(title, Modifier.padding(top = Space.L, bottom = Space.XS), MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)

@Composable
private fun TextAction(label: String, onClick: () -> Unit) =
  Text(label, Modifier.heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = Space.M).wrapContentHeight(), MaterialTheme.colorScheme.primary)
