package com.starsdom.outdoor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录 (§2.12, ux-v3 §8.6 第 5 条) by texted code, or, logged in, 账号 (§8.6 第 7 条): the 头像, 昵称 ›, the number,
 * 同步, 照片只在 Wi-Fi 下上传, 退出登录 and 注销账号. [sendCode], [login], [saveNickname] and [deleteAccount] run off the
 * main thread and throw [OfflineError] with the server's code.
 */
@Composable
fun AccountScreen(
  account: Account?,
  /** The 昵称 as last heard; null until the server has said. */
  nickname: String?,
  /** The 头像 id (null: none), and whether a change of it is on its way (§8.6 第 10 条). */
  avatar: String?,
  avatarBusy: Boolean,
  onPickAvatar: () -> Unit,
  onDropAvatar: () -> Unit,
  saveNickname: (String) -> Unit,
  onNickname: (String) -> Unit,
  onBack: () -> Unit,
  /** Opened by the 队伍页: the reason given says so (C4-18). */
  forTeam: Boolean,
  sendCode: (String) -> Unit,
  login: (String, String) -> Account,
  onLogin: (Account) -> Unit,
  /** In a team now: 退出登录 says it leaves that too (C6-43). */
  inTeam: Boolean,
  onLogout: () -> Unit,
  sync: Boolean,
  /** When 同步 last went through (HH:mm), if ever. */
  lastSync: String?,
  onSync: (Boolean) -> Unit,
  mobilePhotos: Boolean,
  onMobilePhotos: (Boolean) -> Unit,
  deleteAccount: () -> Unit,
  onDeleted: () -> Unit,
  online: Boolean,
) {
  val context = LocalContext.current
  var editing by rememberSaveable { mutableStateOf(false) }
  var deleting by rememberSaveable { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) { Page(Modifier.padding(horizontal = 16.dp)) {
    // C6-18, C6-32: 「← 登录」 / 「← 账号」.
    BackTitle(stringResource(if (account == null) R.string.login_title else R.string.account_title), onBack)
    OfflineStatus(online)
    if (account == null) return@Page LoginForm(forTeam, sendCode, login, onLogin)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      // C6-33: none yet opens the picker straight away; one there asks 换一张 / 不用头像.
      var menu by remember { mutableStateOf(false) }
      Box(
        Modifier.padding(vertical = Space.L).align(Alignment.CenterHorizontally).clip(CircleShape)
          .clickable(enabled = !avatarBusy, role = Role.Button) { if (avatar == null) onPickAvatar() else menu = true }
          .semantics { contentDescription = context.getString(R.string.avatar) },
        contentAlignment = Alignment.Center,
      ) {
        Avatar(nickname.orEmpty(), avatar, 72.dp)
        if (avatarBusy) Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f), CircleShape)) { Spinner(Modifier.align(Alignment.Center)) }
        DropdownMenu(menu, { menu = false }) {
          DropdownMenuItem({ Text(stringResource(R.string.avatar_change)) }, { menu = false; onPickAvatar() }, Modifier.heightIn(min = 48.dp))
          DropdownMenuItem({ Text(stringResource(R.string.avatar_drop)) }, { menu = false; onDropAvatar() }, Modifier.heightIn(min = 48.dp))
        }
      }
      ValueRow(stringResource(R.string.nickname), nickname.orEmpty(), onClick = { editing = true })
      ValueRow(stringResource(R.string.phone), maskedPhone(account.phone))
      Switch(stringResource(R.string.sync), sync) { onSync(!sync) }
      if (sync && lastSync != null) Text(stringResource(R.string.last_sync, lastSync), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      Switch(stringResource(R.string.photos_wifi_only), !mobilePhotos) { onMobilePhotos(!mobilePhotos) }
      // C6-43: 再点一次; the 后果 above it (R10).
      if (inTeam) Text(stringResource(R.string.logout_team), Modifier.padding(top = Space.L), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
      TapAgain(stringResource(R.string.logout), stringResource(R.string.logout_armed), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, onConfirm = onLogout)
      // C6-46: red words at the very bottom, opening C6-47's 小抽屉.
      Text(
        stringResource(R.string.delete_account),
        Modifier.fillMaxWidth().padding(top = Space.XL).heightIn(min = 56.dp).clickable(role = Role.Button) { deleting = true }.padding(horizontal = Space.M).wrapContentHeight(),
        MaterialTheme.colorScheme.error,
      )
    }
  }
    if (editing && account != null) {
      BackHandler { editing = false }
      NicknameSheet(nickname.orEmpty(), saveNickname, { editing = false; onNickname(it) }, { editing = false }, Modifier.align(Alignment.BottomCenter))
    }
    if (deleting && account != null) {
      BackHandler { deleting = false }
      DeleteAccountSheet(deleteAccount, onDeleted, { deleting = false }, Modifier.align(Alignment.BottomCenter))
    }
  }
}

/**
 * 登录 (C6-19…30): +86 before the number; 获取验证码 spins, then counts down, then offers 重新获取; the 6th digit of the
 * code logs in by itself, a spinner where a button would be. What went wrong is said under where it went wrong.
 */
@Composable
private fun ColumnScope.LoginForm(forTeam: Boolean, sendCode: (String) -> Unit, login: (String, String) -> Account, onLogin: (Account) -> Unit) {
  val scope = rememberCoroutineScope()
  val codeFocus = remember { FocusRequester() }
  var phone by rememberSaveable { mutableStateOf("") }
  var code by rememberSaveable { mutableStateOf("") }
  var sending by remember { mutableStateOf(false) }
  var loggingIn by remember { mutableStateOf(false) }
  var sent by rememberSaveable { mutableStateOf(false) }
  // A failure: what it says and whether 重试 comes with it ([loginError]); under the number when 获取验证码 failed.
  var error by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
  var errorOnSend by remember { mutableStateOf(false) }
  // Seconds until another code may be asked for (the server allows one a minute).
  var wait by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait-- } }
  fun fail(onSend: Boolean, e: Throwable) {
    error = loginError(e.errorCode, onSend)
    errorOnSend = onSend
    // A wrong code goes, so typing the right one logs in again by itself.
    if (e.errorCode == "wrong_code") code = ""
  }
  /** The number as the server takes it; not one, C6-24 under it. */
  fun phoneOrSay() = mainlandPhone(phone).also { if (it == null) { error = R.string.reason_phone to false; errorOnSend = true } }
  fun send() {
    val p = phoneOrSay() ?: return
    if (sending || wait > 0) return
    sending = true
    error = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { sendCode(p) } }
        .onSuccess { sent = true; wait = 60; codeFocus.requestFocus() }.onFailure { fail(true, it) }
      sending = false
    }
  }
  fun submit() {
    val p = phoneOrSay() ?: return
    if (code.length < 6 || loggingIn) return
    loggingIn = true
    error = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { login(p, code) } }.onSuccess(onLogin).onFailure { fail(false, it) }
      loggingIn = false
    }
  }
  @Composable fun ErrorHere(onSend: Boolean) = error?.takeIf { errorOnSend == onSend }?.let { (text, retry) ->
    PageError(stringResource(text), if (retry) { { if (onSend) send() else submit() } } else null, Modifier.padding(top = Space.XXS))
  }
  // C6-19, C4-18.
  Text(stringResource(if (forTeam) R.string.login_reason_team else R.string.login_reason), Modifier.padding(top = Space.XS), MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth().padding(top = Space.L), Arrangement.spacedBy(Space.XS), Alignment.CenterVertically) {
    OutlinedTextField(
      phone, { phone = it; if (errorOnSend) error = null }, Modifier.weight(1f), singleLine = true,
      isError = errorOnSend && error?.first == R.string.reason_phone,
      label = { Text(stringResource(R.string.phone)) }, leadingIcon = { Text("+86", Modifier.padding(start = Space.L)) },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
    OutlinedButton(::send, Modifier.heightIn(min = 56.dp), enabled = wait == 0 && !sending) {
      if (sending) Spinner(Modifier.size(20.dp), strokeWidth = 2.dp)
      else Text(if (wait > 0) stringResource(R.string.code_wait, wait) else stringResource(if (sent) R.string.get_code_again else R.string.get_code))
    }
  }
  ErrorHere(onSend = true)
  OutlinedTextField(
    code,
    { typed ->
      code = typed.filter(Char::isDigit).take(6)
      if (!errorOnSend) error = null
      // C6-23: the 6th digit logs in; short of it, nothing to press (#149).
      if (code.length == 6) submit()
    },
    Modifier.fillMaxWidth().padding(top = Space.XS).focusRequester(codeFocus), singleLine = true, enabled = !loggingIn,
    isError = !errorOnSend && error?.first == R.string.reason_code,
    label = { Text(stringResource(R.string.code_hint)) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
  )
  ErrorHere(onSend = false)
  if (loggingIn) Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) { Spinner() }
}

/**
 * C6-24…30: what a failed 获取验证码 ([sending]) or 登录 says in the page, and whether it gets 重试: only the server's
 * fault does. Its own reasons say it all; anything else is the server's (CS-07).
 */
internal fun loginError(code: String?, sending: Boolean): Pair<Int, Boolean> = when (code) {
  "invalid_phone", "wrong_code", "sms_too_frequent", "rate_limited", "offline" -> reasonOf(code) to false
  "sms_unavailable" -> R.string.reason_sms_unavailable to true
  else -> (if (sending) R.string.reason_sms_unavailable else R.string.login_failed_server) to true
}

/**
 * 注销账号 (C6-47, C6-48): what goes and what stays, one icon line each, then 再点一次注销, spinning while it runs.
 * Offline it isn't greyed out: pressed, it says why in the sheet, with 重试.
 */
@Composable
private fun DeleteAccountSheet(delete: () -> Unit, onDeleted: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  fun go() {
    busy = true
    error = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { delete() } }.onSuccess { onDeleted() }.onFailure { error = context.errorText(R.string.result_delete_account_failed, it.errorCode) }
      busy = false
    }
  }
  ActionSheet(stringResource(R.string.delete_account), modifier, onCancel) {
    for ((icon, text) in listOf(
      R.drawable.cloud_off_wght500_24px to R.string.delete_cloud,
      R.drawable.public_wght500_24px to R.string.delete_public,
      R.drawable.group_wght500_24px to R.string.delete_team,
      R.drawable.route_wght500_24px to R.string.delete_local_kept,
    )) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(stringResource(text), Modifier.padding(start = Space.M))
    }
    error?.let { PageError(it, ::go) }
    TapAgain(stringResource(R.string.delete_confirm), stringResource(R.string.delete_armed), Modifier.align(Alignment.End), busy = busy, onConfirm = ::go)
  }
}

/** A row of [label] and its [value], with › when it opens something ([onClick]); ≥ 56 dp (§8.6 第 2 条). */
@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) = Row(
  Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  verticalAlignment = Alignment.CenterVertically,
) {
  Text(label, Modifier.weight(1f))
  Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
  if (onClick != null) Icon(R.drawable.chevron_right_wght500_24px, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 昵称 (C6-37, C6-38): the field with its count; blank or over 12 can't be saved. Saving needs the network: no
 * greying out offline, a failure says so in the sheet with 重试. Saved, it closes ([onSaved]) without a 提示条.
 */
@Composable
private fun NicknameSheet(initial: String, save: (String) -> Unit, onSaved: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var draft by rememberSaveable { mutableStateOf(initial) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val name = nicknameOf(draft)
  fun submit() {
    val n = name ?: return
    busy = true
    error = null
    scope.launch {
      runCatching { withContext(Dispatchers.IO) { save(n) } }.onSuccess { onSaved(n) }.onFailure { error = context.errorText(R.string.result_save_failed, it.errorCode) }
      busy = false
    }
  }
  ActionSheet(stringResource(R.string.nickname), modifier, onCancel, stringResource(R.string.save), name != null && !busy, ::submit) {
    val count = nicknameLength(draft.trim())
    OutlinedTextField(
      draft, { draft = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, isError = count > MAX_NICKNAME,
      label = { Text(stringResource(R.string.nickname)) },
      supportingText = { Text(stringResource(R.string.nickname_count, count, MAX_NICKNAME)) },
    )
    error?.let { PageError(it, ::submit) }
  }
}
