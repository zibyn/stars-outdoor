package com.starsdom.trail.team

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starsdom.trail.MainActivity
import com.starsdom.trail.R
import com.starsdom.trail.account.Avatar
import com.starsdom.trail.account.Crop
import com.starsdom.trail.account.decodePhoto
import com.starsdom.trail.track.DrawerIconButton
import com.starsdom.trail.track.TrackPoint
import com.starsdom.trail.track.haversine
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Icon
import com.starsdom.trail.ui.Page
import com.starsdom.trail.ui.Space
import com.starsdom.trail.ui.Spinner
import com.starsdom.trail.ui.TeamStatus
import com.starsdom.trail.ui.hintAnchor
import java.io.ByteArrayOutputStream

/** MainActivity extra: open the 对话 (a chat notification was tapped). */
const val EXTRA_CHAT = "chat"
private const val CHAT_NOTIFICATION = 6

/** 对话 alerts, by the [TeamSession]'s state. Main thread only. */
object ChatAlerts {
  /** Messages up to this seq were announced already. */
  private var announced = 0L

  /** Notifies [t]'s teammates' messages after [read] not announced yet; none while the 对话 is [open] on screen. */
  fun announce(context: Context, t: Team, read: Long, open: Boolean) {
    val ctx = context.applicationContext
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
 * 对话 (§8.4 第 10–16 条), full screen: ← 「队伍 4827」 邀请 ⓘ; the messages, [outbox] after them (faded; ⚠ 没发出
 * calls [onResend]); with none yet, the code to hand out; after 结束行程, the card with 新队伍. The input row: 📍
 * (turning while [locating]), 🖼, the box and a round send. Tapping a location calls [onFocus]; [loadImage] fetches a
 * photo (or its thumbnail) off the main thread.
 */
@Composable
fun ChatScreen(
  team: Team,
  here: TeamPosition?,
  nowMs: Long,
  outbox: List<Outgoing>,
  onResend: (Outgoing) -> Unit,
  loadImage: suspend (id: String, thumb: Boolean) -> ImageBitmap?,
  /** What's typed in the box. */
  draft: String,
  onDraft: (String) -> Unit,
  onSend: (String) -> Unit,
  onLocation: () -> Unit,
  locating: Boolean,
  onPhoto: () -> Unit,
  onFocus: (lat: Double, lon: Double) -> Unit,
  onInfo: () -> Unit,
  onNewTeam: () -> Unit,
  onClose: () -> Unit,
  /** No location allowed: still in the team, but teammates can't see me (§8.4 第 4 条); [onAllowLocation] asks again. */
  noLocation: Boolean,
  onAllowLocation: () -> Unit,
  online: Boolean,
  reconnecting: Boolean,
) {
  val context = LocalContext.current
  var viewing by remember { mutableStateOf<String?>(null) }
  val list = rememberLazyListState()
  val count = team.messages.size + outbox.size + (if (team.ended) 1 else 0)
  LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }
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
      TeamStatus(online, reconnecting, Modifier.padding(horizontal = Space.L))
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
      // C4-24: nothing said yet, the code to hand out; gone with the first message.
      if (team.messages.isEmpty() && outbox.isEmpty() && !team.ended) Column(Modifier.weight(1f).fillMaxWidth(), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(team.code, Modifier.padding(vertical = Space.M), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"))
        Button(stringResource(R.string.invite), primary = true, onClick = { invite(context, team.code) }, Modifier.padding(horizontal = Space.XXL))
      } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
        items(team.messages, key = { it.seq }) { m ->
          MessageRow(m, team.me, team.members.firstOrNull { it.id == m.from }?.avatar, here, nowMs, loadImage, onView = { viewing = it }, onFocus = onFocus)
        }
        items(outbox, key = { "out${it.id}" }) { o -> OutgoingRow(o) { onResend(o) } }
        // C4-41: the trip is over, the 对话 goes on; 新队伍 starts another.
        if (team.ended) item(key = "ended") {
          Column(Modifier.fillMaxWidth().padding(Space.L).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(Space.M), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.trip_ended), textAlign = TextAlign.Center)
            Button(stringResource(R.string.new_team), primary = true, onClick = onNewTeam)
          }
        }
      }
      // C4-25…28: 📍 🖼, the box, ➤.
      Row(Modifier.fillMaxWidth().hintAnchor().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (locating) Box(Modifier.size(48.dp).semantics { contentDescription = context.getString(R.string.send_location) }, contentAlignment = Alignment.Center) { Spinner(Modifier.size(24.dp)) }
        else DrawerIconButton(R.drawable.location_on_wght500_24px, stringResource(R.string.send_location), onLocation)
        DrawerIconButton(R.drawable.image_wght500_24px, stringResource(R.string.send_photo), onPhoto)
        val placeholder = stringResource(R.string.chat_placeholder)
        BasicTextField(
          draft, { onDraft(it.take(1000)) },
          Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp).semantics { contentDescription = placeholder },
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 4,
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          decorationBox = { inner -> Box { if (draft.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
        )
        Box(
          Modifier.padding(start = Space.XS).size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(role = Role.Button) {
            if (draft.isNotBlank()) {
              val text = draft.trim()
              onDraft("")
              onSend(text)
            }
          }.semantics { contentDescription = context.getString(R.string.send) },
          contentAlignment = Alignment.Center,
        ) { Icon(R.drawable.send_wght500_24px, null, tint = MaterialTheme.colorScheme.onPrimary) }
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
  m: TeamMessage, me: Long, avatar: String?, here: TeamPosition?, nowMs: Long, loadImage: suspend (String, Boolean) -> ImageBitmap?,
  onView: (String) -> Unit, onFocus: (Double, Double) -> Unit,
) {
  // The server's notes (加入 / 退出, 队伍轨迹): centred, no bubble, nobody's.
  if (m.kind == "system") return Text(
    m.text.orEmpty() + " · " + chatTime(m.timeS, nowMs),
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
  )
  val mine = m.from == me
  Bubble(mine, if (mine) null else m.name, avatar, if (mine) null else senderLine(m.name, m.timeS, nowMs)) { bubble ->
    val image = m.image
    if (m.kind == "image" && image != null) {
      val thumb by produceState<ImageBitmap?>(null, image) { value = loadImage(image, true) }
      // C4-30: an empty frame while it loads.
      Box(bubble.size(160.dp).clickable { onView(image) }) {
        thumb?.let { Image(it, stringResource(R.string.image), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
      }
    } else {
      val lat = m.lat
      val lon = m.lon
      val text = if (m.kind == "location") {
        locationLine(if (here != null && lat != null && lon != null) haversine(TrackPoint(0, here.lat, here.lon, null), TrackPoint(0, lat, lon, null)) else null, mine)
      } else m.text.orEmpty()
      Text(
        text,
        bubble.clickable(enabled = lat != null && lon != null) { onFocus(lat!!, lon!!) }.padding(12.dp),
        color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

/** Mine, on its way: faded until the server has it (C4-34…35); ⚠ 没发出 under it, a tap sends it again. */
@Composable
private fun OutgoingRow(o: Outgoing, onResend: () -> Unit) {
  val failed = o.state == SendState.Failed
  Column(Modifier.fillMaxWidth().clickable(enabled = failed, role = Role.Button, onClick = onResend), horizontalAlignment = Alignment.End) {
    Bubble(mine = true, name = null, avatar = null, line = null, Modifier.alpha(if (failed) 1f else 0.5f)) { bubble ->
      if (o.kind == "image") Box(bubble.size(160.dp), contentAlignment = Alignment.Center) {
        // C4-33: the upload's progress on the bubble.
        o.progress?.let { CircularProgressIndicator({ it }) }
      } else Text(if (o.kind == "location") locationLine(null, mine = true) else o.text.orEmpty(), bubble.padding(12.dp), MaterialTheme.colorScheme.onPrimaryContainer)
    }
    if (failed) Text(stringResource(R.string.not_sent_tap), Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp).wrapContentHeight(), MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
  }
}

/** A bubble's row: a teammate's 头像 and [line] (C4-29) beside theirs, mine on the right with neither. */
@Composable
private fun Bubble(mine: Boolean, name: String?, avatar: String?, line: String?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) =
  Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    if (name != null) Avatar(name, avatar, 28.dp, Modifier.padding(end = 8.dp, top = 2.dp))
    Column(Modifier.weight(1f), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
      line?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
      content(Modifier.background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small))
    }
  }

/** 邀请 (§8.4 第 11 条): straight to the system share sheet with [inviteText]. */
fun invite(context: Context, code: String) =
  context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText(code)), context.getString(R.string.invite)))
