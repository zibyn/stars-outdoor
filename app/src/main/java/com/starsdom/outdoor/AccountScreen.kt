package com.starsdom.outdoor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录 (§2.12) by texted code, or, logged in, 账号 (ux-v3 §8.6 第 7 条): the 头像 (its 首字 for now), 昵称 ›, the number,
 * 同步, 照片只在 Wi-Fi 下上传, 退出登录 and 注销账号. [sendCode], [login], [saveNickname] and [deleteAccount] run off the
 * main thread and throw [OfflineError] with the server's code.
 */
@Composable
fun AccountScreen(
  account: Account?,
  /** The 昵称 as last heard; null until the server has said. */
  nickname: String?,
  saveNickname: (String) -> Unit,
  onNickname: (String) -> Unit,
  onBack: () -> Unit,
  sendCode: (String) -> Unit,
  login: (String, String) -> Account,
  onLogin: (Account) -> Unit,
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
  Box(Modifier.fillMaxSize()) { Page(Modifier.padding(horizontal = 16.dp)) {
    // C6-18, C6-32: 「← 登录」 / 「← 账号」.
    Row(verticalAlignment = Alignment.CenterVertically) {
      DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onBack)
      Text(stringResource(if (account == null) R.string.login_title else R.string.account_title), Modifier.padding(start = Space.XS), style = MaterialTheme.typography.titleLarge)
    }
    OfflineStatus(online)
    val scope = rememberCoroutineScope()
    if (account != null) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      // The 头像's place: its 首字 until photos come (#166).
      Box(
        Modifier.padding(vertical = Space.L).size(72.dp).background(semantic.teammate, CircleShape).align(Alignment.CenterHorizontally)
          .semantics { contentDescription = context.getString(R.string.avatar) },
        contentAlignment = Alignment.Center,
      ) { Text(initial(nickname.orEmpty()), color = semantic.stroke, style = MaterialTheme.typography.headlineMedium) }
      ValueRow(stringResource(R.string.nickname), nickname.orEmpty(), onClick = { editing = true })
      ValueRow(stringResource(R.string.phone), maskedPhone(account.phone))
      Switch(stringResource(R.string.sync), sync) { onSync(!sync) }
      if (sync && lastSync != null) Text(stringResource(R.string.last_sync, lastSync), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      Switch(stringResource(R.string.photos_wifi_only), !mobilePhotos) { onMobilePhotos(!mobilePhotos) }
      Button(stringResource(R.string.logout), primary = false, onLogout)
      var confirm by rememberSaveable { mutableStateOf(false) }
      var deleting by rememberSaveable { mutableStateOf(false) }
      var error by rememberSaveable { mutableStateOf<String?>(null) }
      // C6-46: red words at the very bottom.
      // ponytail: still the old confirm in place; C6-47's 小抽屉 comes with its own slice.
      Text(
        if (deleting) "正在注销…" else if (confirm) "确认注销（不可恢复）" else stringResource(R.string.delete_account),
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
          if (!confirm) return@clickable run { confirm = true }
          if (deleting) return@clickable
          deleting = true
          error = null
          scope.launch {
            runCatching { withContext(Dispatchers.IO) { deleteAccount() } }.onSuccess { onDeleted() }.onFailure { error = context.errorText(R.string.result_delete_account_failed, it.errorCode) }
            deleting = false
          }
        }.wrapContentHeight(),
        MaterialTheme.colorScheme.error,
      )
      if (confirm) Text(
        "注销后，服务器上你的轨迹、标注、照片、公开轨迹和队伍对话消息都会删除（队友那边显示为“已注销用户”）。本机数据保留。",
        Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
      )
      error?.let { Text(it, Modifier.padding(top = 12.dp), MaterialTheme.colorScheme.error) }
      }
      return@Page
    }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    // Seconds until another code may be asked for (the server allows one a minute).
    var wait by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait-- } }
    fun <T> call(block: () -> T, done: (T) -> Unit) {
      busy = true
      message = null
      scope.launch {
        runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess(done).onFailure { message = loginError(context, it.errorCode) }
        busy = false
      }
    }
    Text("仅支持中国大陆手机号（+86）。登录后才能使用队伍和同步，其余功能无需登录；未登录时数据只存在本机。", Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    Field("手机号", phone, { phone = it }, KeyboardType.Phone)
    Button(if (wait > 0) "重新获取（${wait} 秒）" else "获取验证码", primary = false, onClick = {
      val p = mainlandPhone(phone)
      if (p == null) message = context.getString(R.string.reason_phone)
      else if (wait == 0 && !busy) call({ sendCode(p) }) { wait = 60 }
    })
    Field("验证码", code, { code = it.filter(Char::isDigit).take(6) }, KeyboardType.NumberPassword)
    Button(if (busy) "正在登录…" else "登录", primary = true, onClick = {
      val p = mainlandPhone(phone)
      if (p == null) message = context.getString(R.string.reason_phone)
      else if (code.length == 6 && !busy) call({ login(p, code) }, onLogin)
    })
    message?.let { Text(it, Modifier.padding(top = 12.dp), MaterialTheme.colorScheme.error) }
  }
    if (editing && account != null) {
      BackHandler { editing = false }
      NicknameSheet(nickname.orEmpty(), saveNickname, { editing = false; onNickname(it) }, { editing = false }, Modifier.align(Alignment.BottomCenter))
    }
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

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType) {
  Text(label, Modifier.padding(top = 16.dp, bottom = 4.dp), MaterialTheme.colorScheme.onSurfaceVariant)
  BasicTextField(
    value, onChange, Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp),
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type),
  )
}

/** C6-24…30: the login's own reasons say it all; anything else 「登录失败 · {原因}」. */
private fun loginError(context: Context, code: String?) =
  if (code in listOf("invalid_phone", "wrong_code", "sms_too_frequent", "sms_unavailable", "rate_limited")) context.getString(reasonOf(code))
  else context.errorText(R.string.result_login_failed, code)
