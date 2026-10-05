package com.starsdom.outdoor

// 队伍对话 (spec §2.11): the 群聊 page, photos shrunk before upload (§3.2), and a notification for teammates' messages.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MainActivity extra: open the 对话 (a chat notification was tapped). */
const val EXTRA_CHAT = "chat"
private const val CHAT_NOTIFICATION = 6

/** 对话 alerts, for the service's socket and the app's own catching up alike. Main thread only. */
object ChatAlerts {
  /** The 群聊 is on screen: its messages need no notification. */
  var open = false
  /** Messages up to this seq were announced already. */
  private var announced = 0L

  /** Notifies [t]'s teammates' messages not read or announced yet. */
  fun announce(context: Context, t: Team) {
    val ctx = context.applicationContext
    val read = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getLong(PREF_TEAM_READ, 0L)
    val fresh = unread(t, maxOf(read, announced))
    if (fresh.isEmpty()) return
    announced = fresh.last().seq
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("chat", "队伍对话", NotificationManager.IMPORTANCE_DEFAULT))
    // 一键求助 had its own channel until #124.
    nm.deleteNotificationChannel("sos")
    if (open) return
    // CLEAR_TOP | SINGLE_TOP: a running app gets the extra in onNewIntent instead of just coming to the front.
    val chatIntent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_CHAT, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(ctx, 1, chatIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val text = fresh.takeLast(5).joinToString("\n") { "${it.name}：${it.summary()}" }
    nm.notify(CHAT_NOTIFICATION, Notification.Builder(ctx, "chat")
      .setSmallIcon(R.drawable.group_fill1_24px)
      .setContentTitle("队伍 ${t.code} 的对话")
      .setContentText(text)
      .setStyle(Notification.BigTextStyle().bigText(text))
      .setContentIntent(tap)
      .setAutoCancel(true)
      .build())
  }

  /** The 对话 was seen: its notification goes. */
  fun seen(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(CHAT_NOTIFICATION)
}

/** The photo at [uri] as a JPEG for the 对话 (§3.2): long side 1600 px, about 300 KB, and no EXIF (no GPS tag). */
fun shrinkPhoto(ctx: Context, uri: Uri): ByteArray {
  val bitmap = decodePhoto(ctx, uri, 1600)
  var quality = 85
  while (true) {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (out.size() <= 400_000 || quality <= 40) return out.toByteArray()
    quality -= 15
  }
}

/**
 * 队伍页 in a team (ux-v2 §4.4): the 群聊, full screen. Its top bar ([onInfo]) opens 队伍信息. Tapping a location calls
 * [onFocus]; [loadImage] fetches a photo (or its thumbnail) off the main thread.
 */
@Composable
fun ChatScreen(
  team: Team,
  /** Where this phone is, for 距你 on location messages; null if unknown. */
  here: TeamPosition?,
  loadImage: suspend (id: String, thumb: Boolean) -> ImageBitmap?,
  /** What's typed in the box; kept by the caller so a failed send can put it back after the page closed (#70). */
  draft: String,
  onDraft: (String) -> Unit,
  onSend: (String) -> Unit,
  onLocation: () -> Unit,
  onPhoto: () -> Unit,
  onFocus: (lat: Double, lon: Double) -> Unit,
  onInfo: () -> Unit,
  onClose: () -> Unit,
  /** No location allowed: still in the team, but teammates can't see me (§8.4 第 4 条); [onAllowLocation] asks again. */
  noLocation: Boolean,
  onAllowLocation: () -> Unit,
  online: Boolean,
) {
  val context = LocalContext.current
  var viewing by remember { mutableStateOf<String?>(null) }
  val list = rememberLazyListState()
  LaunchedEffect(team.messages.size) { if (team.messages.isNotEmpty()) list.animateScrollToItem(team.messages.size - 1) }
  Box(Modifier.fillMaxSize()) {
    Page(Modifier.imePadding()) {
      // C4-20…23: ←, 「队伍 4827」 over 「3 人」 / 「已结束」, 邀请 and ⓘ.
      Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onClose)
        Column(Modifier.weight(1f).heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
          Text(stringResource(R.string.team_chat_title, team.code))
          Text(
            if (team.ended) stringResource(R.string.team_ended) else stringResource(R.string.team_members_count, team.members.size),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
          )
        }
        if (!team.ended) DrawerIconButton(R.drawable.share_wght500_24px, stringResource(R.string.invite)) { invite(context, team.code) }
        DrawerIconButton(R.drawable.info_wght500_24px, stringResource(R.string.team_info), onInfo)
      }
      OfflineStatus(online, Modifier.padding(horizontal = Space.L))
      // C4-19: stays while it's so, not a 提示条 that goes.
      if (noLocation && !team.ended) Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = Space.L),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.hint_team_no_permission), Modifier.weight(1f))
        Text(
          stringResource(R.string.action_open_settings),
          Modifier.heightIn(min = 48.dp).clickable(onClick = onAllowLocation).padding(start = Space.M).wrapContentHeight(),
          MaterialTheme.colorScheme.primary,
        )
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
        items(team.messages, key = { it.seq }) { m ->
          MessageRow(m, team.me, team.members.firstOrNull { it.id == m.from }?.avatar, here, loadImage, onView = { viewing = it }, onFocus = onFocus)
        }
      }
      Row(Modifier.fillMaxWidth().hintAnchor().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
          draft, { onDraft(it.take(1000)) }, Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp),
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 4,
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
        Button("发送", primary = true, onClick = {
          if (draft.isNotBlank()) {
            val text = draft.trim()
            // Cleared first: a failure can come back before onSend returns.
            onDraft("")
            onSend(text)
          }
        }, Modifier.padding(start = 8.dp))
      }
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("发我的位置", primary = false, onClick = onLocation, Modifier.weight(1f))
        Button("图片", primary = false, onClick = onPhoto, Modifier.weight(1f))
      }
    }
    viewing?.let { id ->
      BackHandler { viewing = null }
      val photo by produceState<ImageBitmap?>(null, id) { value = loadImage(id, false) }
      Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim).clickable { viewing = null }, contentAlignment = Alignment.Center) {
        photo?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Spinner()
      }
    }
  }
}

@Composable
private fun MessageRow(
  m: TeamMessage, me: Long, avatar: String?, here: TeamPosition?, loadImage: suspend (String, Boolean) -> ImageBitmap?, onView: (String) -> Unit, onFocus: (Double, Double) -> Unit,
) {
  // The server's note (队伍轨迹 changes): centred, no bubble, nobody's.
  if (m.kind == "system") return Text(
    m.text.orEmpty() + " · " + timeText(m.timeS),
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
  )
  val mine = m.from == me
  // Teammates' bubbles with their 头像 (§8.4 第 7 条); one who left shows the 首字.
  Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    if (!mine) Avatar(m.name, avatar, 28.dp, Modifier.padding(end = 8.dp, top = 2.dp))
    Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
      Text((if (mine) "我" else m.name) + " · " + timeText(m.timeS), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      val bubble = Modifier.background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
      val image = m.image
      if (m.kind == "image" && image != null) {
        val thumb by produceState<ImageBitmap?>(null, image) { value = loadImage(image, true) }
        Box(bubble.size(160.dp).clickable { onView(image) }, contentAlignment = Alignment.Center) {
          thumb?.let { Image(it, "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        val lat = m.lat
        val lon = m.lon
        val text = when (m.kind) {
          "location" -> {
            val away = if (!mine && here != null && lat != null && lon != null) "距你 " + distanceText(haversine(TrackPoint(0, here.lat, here.lon, null), TrackPoint(0, lat, lon, null))) else null
            locationLine(m, away)
          }
          else -> m.text.orEmpty()
        }
        Text(
          text,
          bubble.clickable(enabled = lat != null && lon != null) { onFocus(lat!!, lon!!) }.padding(12.dp),
          color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

/** 14:05 today, else 9月28日 14:05. */
private fun timeText(timeS: Long): String {
  val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
  val at = Date(timeS * 1000)
  return SimpleDateFormat(if (day.format(at) == day.format(Date())) "HH:mm" else "M月d日 HH:mm", Locale.CHINA).format(at)
}

/** 邀请 (§8.4 第 11 条): straight to the system share sheet with [inviteText]. */
fun invite(context: Context, code: String) =
  context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText(code)), context.getString(R.string.invite)))
