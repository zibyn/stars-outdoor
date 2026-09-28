package dev.stars.outdoor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录 (§2.12) by texted code, or, logged in, the number, 同步, 退出登录 and 注销账号. [sendCode], [login] and
 * [deleteAccount] run off the main thread and throw [OfflineError] with the server's code.
 */
@Composable
fun AccountScreen(
  account: Account?,
  sendCode: (String) -> Unit,
  login: (String, String) -> Account,
  onLogin: (Account) -> Unit,
  onLogout: () -> Unit,
  sync: Boolean,
  onSync: (Boolean) -> Unit,
  mobilePhotos: Boolean,
  onMobilePhotos: (Boolean) -> Unit,
  deleteAccount: () -> Unit,
  onDeleted: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("账号", style = TextStyle(fontSize = 22.sp))
    val scope = rememberCoroutineScope()
    if (account != null) {
      BasicText("已登录：${account.phone}", Modifier.padding(top = 16.dp))
      Button(if (sync) "同步已开启 · 点击关闭" else "开启同步", primary = !sync, { onSync(!sync) })
      BasicText("同步轨迹和标注（含照片）；离线地图和设置不同步。第一次开启时会上传本机已有的数据。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      if (sync) Button(if (mobilePhotos) "照片：Wi-Fi 和移动网络都上传" else "照片：仅在 Wi-Fi 下上传", primary = false, { onMobilePhotos(!mobilePhotos) })
      Button("退出登录", primary = false, onLogout)
      BasicText("退出登录后，轨迹和标注仍保留在本机，只是不再同步。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
      var confirm by rememberSaveable { mutableStateOf(false) }
      var deleting by rememberSaveable { mutableStateOf(false) }
      var error by rememberSaveable { mutableStateOf<String?>(null) }
      Button(if (deleting) "请稍候…" else if (confirm) "确认注销（不可恢复）" else "注销账号", primary = false, onClick = {
        if (!confirm) return@Button run { confirm = true }
        if (deleting) return@Button
        deleting = true
        error = null
        scope.launch {
          runCatching { withContext(Dispatchers.IO) { deleteAccount() } }.onSuccess { onDeleted() }.onFailure { error = loginMessage((it as? OfflineError)?.code) }
          deleting = false
        }
      })
      if (confirm) BasicText(
        "注销后，服务器上你的轨迹、标注、照片、公开轨迹和队伍对话消息都会删除（队友那边显示为“已注销用户”）。本机数据保留。",
        Modifier.padding(top = 8.dp), style = TextStyle(color = Color(0xFFE4572E), fontSize = 12.sp),
      )
      error?.let { BasicText(it, Modifier.padding(top = 12.dp), style = TextStyle(color = Color(0xFFE4572E))) }
      return@Column
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
        runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess(done).onFailure { message = loginMessage((it as? OfflineError)?.code) }
        busy = false
      }
    }
    BasicText("仅支持中国大陆手机号（+86）。登录后才能使用队伍和同步，其余功能无需登录；未登录时数据只存在本机。", Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp))
    Field("手机号", phone, { phone = it }, KeyboardType.Phone)
    Button(if (wait > 0) "重新获取（${wait} 秒）" else "获取验证码", primary = false, onClick = {
      val p = mainlandPhone(phone)
      if (p == null) message = loginMessage("invalid_phone")
      else if (wait == 0 && !busy) call({ sendCode(p) }) { wait = 60 }
    })
    Field("验证码", code, { code = it.filter(Char::isDigit).take(6) }, KeyboardType.NumberPassword)
    Button(if (busy) "请稍候…" else "登录", primary = true, onClick = {
      val p = mainlandPhone(phone)
      if (p == null) message = loginMessage("invalid_phone")
      else if (code.length == 6 && !busy) call({ login(p, code) }, onLogin)
    })
    message?.let { BasicText(it, Modifier.padding(top = 12.dp), style = TextStyle(color = Color(0xFFE4572E))) }
  }
}

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType) {
  BasicText(label, Modifier.padding(top = 16.dp, bottom = 4.dp), style = TextStyle(color = Color.Gray))
  BasicTextField(
    value, onChange, Modifier.fillMaxWidth().border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).padding(12.dp),
    textStyle = TextStyle(fontSize = 16.sp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = type),
  )
}
