package com.starsdom.trail.account

// 头像 (ux-v3 §8.4 第 6–8 条): picked with the system picker, cropped to a circle here, uploaded as a 256×256 JPEG;
// shown on the map's dots, in the 对话, the member rows, 账号 and 设置. Cached by id: an id never changes its image.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.starsdom.trail.R
import com.starsdom.trail.team.fitLongSide
import com.starsdom.trail.ui.Button
import com.starsdom.trail.ui.Space
import com.starsdom.trail.ui.Spinner
import com.starsdom.trail.ui.semantic
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** SharedPreferences: the account's 头像 id as last heard from the server (absent: none). */
const val PREF_AVATAR = "avatar"

/** The side of the JPEG uploaded (§8.4 第 6 条: about 20 KB). */
const val AVATAR_SIDE = 256

/**
 * 头像 by id: in memory, then in [dir] (one's own is written there on upload, so it's on this phone without the
 * network), then [fetch]ed and kept. Offline with neither, null: the 首字 shows (§8.4 第 8 条).
 */
// ponytail: teammates' old ones stay in dir (about 20 KB each); prune by age if it ever adds up.
class AvatarCache(private val dir: File, private val fetch: suspend (String) -> ByteArray) {
  private val memory = LruCache<String, ImageBitmap>(64)

  /** Forgets every kept image (退出登录, 注销). */
  fun clear() {
    memory.evictAll()
    dir.deleteRecursively()
  }

  /** Keeps [jpeg] as [id]'s image. */
  fun keep(id: String, jpeg: ByteArray) {
    dir.mkdirs()
    File(dir, "$id.tmp").apply { writeBytes(jpeg) }.renameTo(File(dir, "$id.jpg"))
  }

  fun cached(id: String): ImageBitmap? = memory.get(id)

  suspend fun load(id: String): ImageBitmap? = memory.get(id) ?: withContext(Dispatchers.IO) {
    val file = File(dir, "$id.jpg")
    if (!file.exists()) runCatching { keep(id, fetch(id)) }
    runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
  }?.also { memory.put(id, it) }
}

/** The [AvatarCache] the app keeps; null (previews, tests): 首字 only. */
val LocalAvatars = staticCompositionLocalOf<AvatarCache?> { null }

/**
 * Someone's 头像 [size] across: their photo edged in 队友紫 (§8.4 第 7 条), else their 首字 on 队友紫; once they
 * stopped sharing ([sharing] false), a hollow grey ring with the 首字.
 */
@Composable
fun Avatar(name: String, avatar: String?, size: Dp, modifier: Modifier = Modifier, sharing: Boolean = true) {
  val cache = LocalAvatars.current
  val photo by produceState(avatar?.let { cache?.cached(it) }, avatar, cache) {
    value = avatar?.let { cache?.load(it) }
    // Offline with nothing kept: the 首字 meanwhile, and another try now and then (§8.4 第 8 条).
    while (avatar != null && cache != null && value == null) {
      delay(30_000)
      value = cache.load(avatar)
    }
  }
  val shown = photo.takeIf { sharing }
  Box(
    modifier.size(size).then(
      when {
        !sharing -> Modifier.border(3.dp, MaterialTheme.colorScheme.outline, CircleShape)
        shown != null -> Modifier.border(2.dp, semantic.teammate, CircleShape)
        else -> Modifier.background(semantic.teammate, CircleShape)
      },
    ),
    contentAlignment = Alignment.Center,
  ) {
    if (shown != null) Image(shown, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
    else Text(
      initial(name), color = if (sharing) semantic.stroke else MaterialTheme.colorScheme.onSurfaceVariant,
      style = if (size >= 56.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
    )
  }
}

/**
 * Where the photo sits under the 取景框 (a circle [d] px across): [scale] screen px per photo px, and its centre
 * [x], [y] px from the circle's.
 */
data class Crop(val scale: Float, val x: Float, val y: Float)

/** A [w]×[h] photo as the crop opens: centred, just covering the circle. */
fun startCrop(w: Int, h: Int, d: Float) = Crop(d / minOf(w, h), 0f, 0f)

/** Kept covering the circle: no smaller than [startCrop], not dragged past its edge. */
fun Crop.clamped(w: Int, h: Int, d: Float): Crop {
  val s = maxOf(scale, d / minOf(w, h))
  val sx = (w * s - d) / 2
  val sy = (h * s - d) / 2
  return Crop(s, x.coerceIn(-sx, sx), y.coerceIn(-sy, sy))
}

/** After a gesture: dragged by [pan], zoomed by [zoom] about [centroid] (both from the circle's centre), [clamped]. */
fun Crop.transformed(centroid: Offset, pan: Offset, zoom: Float, w: Int, h: Int, d: Float) =
  Crop(scale * zoom, (x - centroid.x) * zoom + centroid.x + pan.x, (y - centroid.y) * zoom + centroid.y + pan.y).clamped(w, h, d)

/** The square of the photo inside the circle, in photo px. */
fun Crop.source(w: Int, h: Int, d: Float): Rect {
  val c = Offset(w / 2f - x / scale, h / 2f - y / scale)
  return Rect(c, d / 2 / scale)
}

/** [source] of [bitmap] as the uploaded JPEG: [AVATAR_SIDE] square. */
fun avatarJpeg(bitmap: Bitmap, source: Rect): ByteArray {
  val out = Bitmap.createBitmap(AVATAR_SIDE, AVATAR_SIDE, Bitmap.Config.ARGB_8888)
  android.graphics.Canvas(out).drawBitmap(
    bitmap, android.graphics.Rect(source.left.toInt(), source.top.toInt(), source.right.toInt(), source.bottom.toInt()),
    android.graphics.Rect(0, 0, AVATAR_SIDE, AVATAR_SIDE), Paint(Paint.FILTER_BITMAP_FLAG),
  )
  return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

/**
 * 头像裁剪页 (C6-34): full screen on black, the photo under a round 取景框, dragged and pinched (never smaller than
 * the circle, never turned). 用这张 hands [onUse] the JPEG; 取消 and back drop it. A photo that can't be read is
 * [onUnreadable].
 */
@Composable
fun AvatarCropScreen(uri: Uri, onCancel: () -> Unit, onUse: (ByteArray) -> Unit, onUnreadable: () -> Unit) {
  BackHandler(onBack = onCancel)
  val context = LocalContext.current
  val bitmap by produceState<Bitmap?>(null, uri) {
    value = withContext(Dispatchers.IO) { runCatching { decodePhoto(context, uri, 1600) }.getOrNull() }
    if (value == null) onUnreadable()
  }
  Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val px = with(LocalDensity.current) { Size(maxWidth.toPx(), maxHeight.toPx()) }
      val d = minOf(px.width, px.height) - with(LocalDensity.current) { 48.dp.toPx() }
      val b = bitmap
      var crop by remember(b, d) { mutableStateOf(b?.let { startCrop(it.width, it.height, d) }) }
      if (b == null) Spinner(Modifier.align(Alignment.Center))
      else {
        val image = remember(b) { b.asImageBitmap() }
        val centre = Offset(px.width / 2, px.height / 2)
        Canvas(Modifier.fillMaxSize().pointerInput(b, d) {
          detectTransformGestures { at, pan, zoom, _ -> crop = crop?.transformed(at - centre, pan, zoom, b.width, b.height, d) }
        }) {
          val c = crop ?: return@Canvas
          withTransform({
            translate(centre.x + c.x - b.width * c.scale / 2, centre.y + c.y - b.height * c.scale / 2)
            scale(c.scale, c.scale, Offset.Zero)
          }) { drawImage(image, IntOffset.Zero, IntSize(b.width, b.height)) }
          // Dimmed outside the circle.
          val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addOval(Rect(centre, d / 2))
          }
          drawPath(outside, Color.Black.copy(alpha = 0.6f))
          drawCircle(Color.White, d / 2, centre, style = Stroke(1.dp.toPx()))
        }
      }
      Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (b != null) Text(stringResource(R.string.avatar_crop_hint), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().padding(top = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
          Button(stringResource(R.string.cancel), primary = false, onCancel, Modifier.weight(1f))
          Button(stringResource(R.string.avatar_use), primary = true, { crop?.let { c -> b?.let { onUse(avatarJpeg(it, c.source(it.width, it.height, d))) } } }, Modifier.weight(1f))
        }
      }
    }
  }
}

/** The photo at [uri], its long side at most [longSide] px, turned upright by its EXIF from API 28. */
fun decodePhoto(ctx: Context, uri: Uri, longSide: Int): Bitmap = if (Build.VERSION.SDK_INT >= 28) {
  ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
    val (w, h) = fitLongSide(info.size.width, info.size.height, longSide)
    decoder.setTargetSize(w, h)
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
  }
} else {
  // ponytail: API 26–27 only subsample, and ignore EXIF rotation; ImageDecoder does both from 28.
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
  ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    ?: throw IllegalArgumentException("not an image")
}
