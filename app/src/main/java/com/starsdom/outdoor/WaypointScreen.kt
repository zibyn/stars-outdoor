package com.starsdom.outdoor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Edit a 标注: name, description, photo (§2.4). Edits are held by the caller and saved when it closes. */
@Composable
fun WaypointScreen(
  waypoint: Waypoint,
  name: String,
  description: String,
  onName: (String) -> Unit,
  onDescription: (String) -> Unit,
  onPickPhoto: () -> Unit,
  onDelete: () -> Unit,
  /** 下载这附近 (§2.3), around this 标注. */
  onDownload: () -> Unit,
  onDone: () -> Unit,
) {
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicText("标注", style = TextStyle(fontSize = 22.sp))
    BasicText(
      // Imported 标注 may have no time.
      (if (waypoint.timeMs != 0L) SimpleDateFormat("yyyy-MM-dd HH:mm  ", Locale.ROOT).format(Date(waypoint.timeMs)) else "") +
        String.format(Locale.ROOT, "%.5f, %.5f", waypoint.lat, waypoint.lon) + (waypoint.ele?.let { "  ${Math.round(it)} m" } ?: ""),
      style = TextStyle(color = Color.Gray, fontSize = 12.sp),
    )
    Field("名称", name, onName, singleLine = true)
    Field("描述", description, onDescription, singleLine = false)
    val photo = remember(waypoint.photo) { waypoint.photo?.let(::loadThumbnail) }
    if (photo != null) {
      Image(photo.asImageBitmap(), "照片", Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 16.dp), contentScale = ContentScale.Fit)
    }
    Spacer(Modifier.weight(1f))
    Button("下载这附近", primary = false, onDownload, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      // ux-v2 §6.3: only a second tap within 3 s deletes.
      TapAgain("删除", "再点一次删除", Modifier.weight(1f), onDelete)
      Button(if (photo == null) "添加照片" else "更换照片", primary = false, onPickPhoto, Modifier.weight(1f))
      Button("完成", primary = true, onDone, Modifier.weight(1f))
    }
  }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean) {
  BasicText(label, Modifier.padding(top = 16.dp, bottom = 4.dp), style = TextStyle(color = Color.Gray))
  BasicTextField(
    value, onChange, Modifier.fillMaxWidth().border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).padding(12.dp),
    textStyle = TextStyle(fontSize = 16.sp), singleLine = singleLine, minLines = if (singleLine) 1 else 3,
  )
}

// ponytail: ignores EXIF rotation; honour it when portrait photos show sideways.
private fun loadThumbnail(path: String) = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(path, it) }
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
  BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
