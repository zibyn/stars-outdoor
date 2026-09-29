package dev.stars.outdoor

// 队伍对话 and 一键求助 (spec §2.11): the half-screen drawer, photos shrunk before upload (§3.2), and the
// alerts: a notification for teammates' messages, the alarm for a 求助.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MainActivity extra: open the 对话 (a chat or 求助 notification was tapped). */
const val EXTRA_CHAT = "chat"
private const val CHAT_NOTIFICATION = 6
private const val SOS_NOTIFICATION = 7

/** 对话 alerts, for the service's socket and the app's own catching up alike. Main thread only. */
object ChatAlerts {
  /** The drawer is on screen: its messages need no notification (a 求助 still rings). */
  var open = false
  /** Messages up to this seq were announced already. */
  private var announced = 0L
  private var ringtone: Ringtone? = null
  private var ringing = false
  private val handler = Handler(Looper.getMainLooper())
  private val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

  /** Notifies [t]'s teammates' messages not read or announced yet; a recent 求助 among them rings. */
  fun announce(context: Context, t: Team) {
    val ctx = context.applicationContext
    val read = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getLong(PREF_TEAM_READ, 0L)
    val fresh = unread(t, maxOf(read, announced))
    if (fresh.isEmpty()) return
    announced = fresh.last().seq
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("chat", "队伍对话", NotificationManager.IMPORTANCE_DEFAULT))
    // Sound and vibration are ours (ring), so a 求助 still rings with the channel muted or notifications denied.
    nm.createNotificationChannel(NotificationChannel("sos", "一键求助", NotificationManager.IMPORTANCE_HIGH).apply {
      setSound(null, null)
      enableVibration(false)
      setBypassDnd(true)
    })
    // CLEAR_TOP | SINGLE_TOP: a running app gets the extra in onNewIntent instead of just coming to the front.
    val chatIntent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CHAT, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(ctx, 1, chatIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fresh.lastOrNull { it.kind == "sos" }?.let { sos ->
      // Unread rings, also after hours out of signal; only one from long ago (joining a team with history) doesn't.
      if (System.currentTimeMillis() / 1000 - sos.timeS < 12 * 3600) ring(ctx)
      nm.notify(SOS_NOTIFICATION, Notification.Builder(ctx, "sos")
        .setSmallIcon(R.drawable.notifications_active_fill1_24px)
        .setContentTitle("${sos.name} 在求助")
        .setContentText(listOfNotNull(sos.battery?.let { "电量 $it%" }, if (sos.lat != null) "点这里看位置" else "位置未知").joinToString(" · "))
        .setCategory(Notification.CATEGORY_ALARM)
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build())
    }
    val chat = fresh.filter { it.kind != "sos" }
    if (open || chat.isEmpty()) return
    val text = chat.takeLast(5).joinToString("\n") { "${it.name}：${it.summary()}" }
    nm.notify(CHAT_NOTIFICATION, Notification.Builder(ctx, "chat")
      .setSmallIcon(R.drawable.group_fill1_24px)
      .setContentTitle("队伍 ${t.code} 的对话")
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setContentIntent(tap)
      .setAutoCancel(true)
      .build())
  }

  /** The 对话 was seen: its notifications go, and a 求助's alarm stops. */
  fun seen(context: Context) {
    val ctx = context.applicationContext
    ctx.getSystemService(NotificationManager::class.java).run { cancel(CHAT_NOTIFICATION); cancel(SOS_NOTIFICATION) }
    stopAlarm(ctx)
  }

  /** The alarm ringtone at alarm volume (it sounds in silent mode) and vibration, for a minute or until [seen]. */
  private fun ring(ctx: Context) {
    stopAlarm(ctx)
    ringing = true
    val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
      audioAttributes = alarm
      if (Build.VERSION.SDK_INT >= 28) isLooping = true
      play()
    }
    @Suppress("DEPRECATION") // VibratorManager needs API 31; this works on all.
    ctx.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0), alarm)
    handler.postDelayed({ stopAlarm(ctx) }, 60_000L)
  }

  private fun stopAlarm(ctx: Context) {
    if (!ringing) return
    ringing = false
    handler.removeCallbacksAndMessages(null)
    ringtone?.stop()
    ringtone = null
    ctx.getSystemService(Vibrator::class.java).cancel()
  }
}

/** The photo at [uri] as a JPEG for the 对话 (§3.2): long side 1600 px, about 300 KB, and no EXIF (no GPS tag). */
fun shrinkPhoto(ctx: Context, uri: Uri): ByteArray {
  val bitmap = if (Build.VERSION.SDK_INT >= 28) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
      val (w, h) = fitLongSide(info.size.width, info.size.height, 1600)
      decoder.setTargetSize(w, h)
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
  } else {
    // ponytail: API 26–27 only subsample, and ignore EXIF rotation; ImageDecoder does both from 28.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
      ?: throw IllegalArgumentException("not an image")
  }
  var quality = 85
  while (true) {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (out.size() <= 400_000 || quality <= 40) return out.toByteArray()
    quality -= 15
  }
}

/**
 * 队伍对话 (§2.11) as a drawer over the lower half of the map, dragged up to full screen. Tapping a location
 * or 求助 calls [onFocus]; [loadImage] fetches a photo (or its thumbnail) off the main thread. [sosNote]
 * is how the last 求助 is getting on; [onSosRetry], if set, sends it again at a tap.
 */
@Composable
fun ChatDrawer(
  team: Team,
  sosNote: String?,
  /** Where this phone is, for 距你 on location messages; null if unknown. */
  here: TeamPosition?,
  loadImage: suspend (id: String, thumb: Boolean) -> ImageBitmap?,
  onSend: (String) -> Unit,
  onLocation: () -> Unit,
  onPhoto: () -> Unit,
  onSos: () -> Unit,
  onSosRetry: (() -> Unit)?,
  onFocus: (lat: Double, lon: Double) -> Unit,
  onClose: () -> Unit,
) {
  var full by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }
  var viewing by remember { mutableStateOf<String?>(null) }
  val list = rememberLazyListState()
  LaunchedEffect(team.messages.size) { if (team.messages.isNotEmpty()) list.animateScrollToItem(team.messages.size - 1) }
  Box(Modifier.fillMaxSize()) {
    HalfDrawer(full, { full = it }, onClose) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("队伍 ${team.code} 的对话" + if (team.ended) "（行程已结束）" else "", Modifier.weight(1f), style = TextStyle(fontSize = 16.sp))
        BasicText("关闭", Modifier.clickable(onClick = onClose).padding(8.dp), style = TextStyle(color = Color.Gray))
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
        items(team.messages, key = { it.seq }) { m ->
          MessageRow(m, team.me, here, loadImage, onView = { viewing = it }, onFocus = { lat, lon -> full = false; onFocus(lat, lon) })
        }
      }
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
          draft, { draft = it.take(1000) }, Modifier.weight(1f).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).padding(10.dp),
          textStyle = TextStyle(fontSize = 16.sp), maxLines = 4,
        )
        Button("发送", primary = true, onClick = { if (draft.isNotBlank()) { onSend(draft.trim()); draft = "" } }, Modifier.padding(start = 8.dp))
      }
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("位置", primary = false, onClick = onLocation, Modifier.weight(1f))
        Button("图片", primary = false, onClick = onPhoto, Modifier.weight(1f))
        // Held 1.5 s (ux-v2 §5): a 求助 must not go out by a brush of the glove.
        HoldKey("求助", "按住 1.5 秒", 1500, Red, Modifier.weight(1f), onSos)
      }
      Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
          sosNote ?: if (team.ended) "行程已结束：求助仍会发到对话里，但不会让队友手机响铃" else "按住发出，队友手机会响铃。只通知队友，不联系救援",
          Modifier.weight(1f, fill = false).padding(vertical = 6.dp),
          style = TextStyle(color = if (sosNote != null) Red else Color.Gray, fontSize = 12.sp),
        )
        onSosRetry?.let {
          BasicText(" · ", style = TextStyle(color = Red, fontSize = 12.sp))
          BasicText("重试", Modifier.heightIn(min = 56.dp).widthIn(min = 56.dp).clickable(onClick = it).padding(horizontal = 8.dp).wrapContentHeight(), style = TextStyle(color = Red, fontSize = 14.sp, textAlign = TextAlign.Center))
        }
      }
    }
    viewing?.let { id ->
      BackHandler { viewing = null }
      val photo by produceState<ImageBitmap?>(null, id) { value = loadImage(id, false) }
      Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewing = null }, contentAlignment = Alignment.Center) {
        photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
          ?: BasicText("加载中…", style = TextStyle(color = Color.White))
      }
    }
  }
}

@Composable
private fun MessageRow(
  m: TeamMessage, me: Long, here: TeamPosition?, loadImage: suspend (String, Boolean) -> ImageBitmap?, onView: (String) -> Unit, onFocus: (Double, Double) -> Unit,
) {
  // The server's note (队伍轨迹 changes): centred, no bubble, nobody's.
  if (m.kind == "system") return BasicText(
    m.text.orEmpty() + " · " + timeText(m.timeS),
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    style = TextStyle(color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center),
  )
  val mine = m.from == me
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
    BasicText((if (mine) "我" else m.name) + " · " + timeText(m.timeS), style = TextStyle(color = Color.Gray, fontSize = 11.sp))
    val sos = m.kind == "sos"
    val bubble = Modifier.background(if (sos) Red else if (mine) Color(0xFFDFF3E8) else Color(0xFFF0F0F0), RoundedCornerShape(8.dp))
    val image = m.image
    if (m.kind == "image" && image != null) {
      val thumb by produceState<ImageBitmap?>(null, image) { value = loadImage(image, true) }
      Box(bubble.size(160.dp).clickable { onView(image) }, contentAlignment = Alignment.Center) {
        thumb?.let { Image(it, "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: BasicText("图片", style = TextStyle(color = Color.Gray))
      }
    } else {
      val lat = m.lat
      val lon = m.lon
      val text = when (m.kind) {
        "location" -> {
          val away = if (!mine && here != null && lat != null && lon != null) "距你 " + distanceText(haversine(TrackPoint(0, here.lat, here.lon, null), TrackPoint(0, lat, lon, null))) else null
          listOfNotNull("位置", away, "点这里看").joinToString(" · ")
        }
        "sos" -> m.name + " " + m.summary() + if (lat != null) " · 点这里看位置" else " · 位置未知"
        else -> m.text.orEmpty()
      }
      BasicText(
        text,
        bubble.clickable(enabled = lat != null && lon != null) { onFocus(lat!!, lon!!) }.padding(10.dp),
        style = TextStyle(color = if (sos) Color.White else Color.Black, fontSize = 15.sp),
      )
    }
  }
}

private val Red = Color(0xFFE4572E)

/** 14:05 today, else 9月28日 14:05. */
private fun timeText(timeS: Long): String {
  val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
  val at = Date(timeS * 1000)
  return SimpleDateFormat(if (day.format(at) == day.format(Date())) "HH:mm" else "M月d日 HH:mm", Locale.CHINA).format(at)
}
