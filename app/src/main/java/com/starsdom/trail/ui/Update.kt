package com.starsdom.trail.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import com.starsdom.trail.BuildConfig
import com.starsdom.trail.OfflineError
import com.starsdom.trail.networkCode
import com.starsdom.trail.offline.storage
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 应用内更新 (§2.13, #51): GitHub Release, checked at most once a day; a new one only puts a dot on 设置, and 关于
// downloads it, checks its SHA-256 and hands it to the system installer.

private const val LATEST_RELEASE = "https://api.github.com/repos/zibyn/stars-trail/releases/latest"
private const val PREF_RELEASE = "latest_release"
private const val PREF_RELEASE_AT = "latest_release_checked_at"
private const val DAY_MS = 24 * 60 * 60 * 1000L

/** A newer build: its [name] (1.2.0), the APK's [url] and [sha256] (hex). */
data class Release(val name: String, val url: String, val sha256: String)

/** The newer build, if any; [UpgradePrompt]'s 去升级 and the 设置 dot lead to it. */
object Updates {
  val available = MutableStateFlow<Release?>(null)
}

/** A release tag's versionCode, as app/build.gradle.kts makes it: v1.2.3 → 10203; null if it isn't one. */
fun versionCodeOf(tag: String): Long? =
  Regex("""v(\d+)\.(\d+)\.(\d+)""").matchEntire(tag)?.destructured?.let { (a, b, c) -> a.toLong() * 10000 + b.toLong() * 100 + c.toLong() }

/** GitHub's latest release ([json]) when it's newer than [current] and has an APK with its SHA-256 (GitHub's own digest). */
fun newerRelease(json: String, current: Long): Release? = runCatching {
  val release = Json.parseToJsonElement(json).jsonObject
  val tag = release["tag_name"]!!.jsonPrimitive.content
  if ((versionCodeOf(tag) ?: return null) <= current) return null
  val apk = release["assets"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content.endsWith(".apk") }
  Release(tag.removePrefix("v"), apk["browser_download_url"]!!.jsonPrimitive.content, apk["digest"]!!.jsonPrimitive.contentOrNull!!.takeIf { it.startsWith("sha256:") }!!.removePrefix("sha256:"))
}.getOrNull()

/**
 * Asks GitHub once a day, or now when [force]d (关于's 检查更新, 强制升级's 去升级); in between, or when GitHub doesn't
 * answer (offline, its 60-an-hour limit), the answer from last time, so the dot outlives a restart. Forced, not
 * answering is an [OfflineError] (its [networkCode]). The newer build, if any. Off the main thread.
 */
suspend fun checkForUpdate(prefs: SharedPreferences, force: Boolean = false): Release? {
  val now = System.currentTimeMillis()
  var json = prefs.getString(PREF_RELEASE, null)
  if (force || now - prefs.getLong(PREF_RELEASE_AT, 0) >= DAY_MS) try {
    json = storage.get(LATEST_RELEASE) { timeout { socketTimeoutMillis = 15_000 } }.let {
      when (it.status) {
        HttpStatusCode.OK -> it.bodyAsText()
        HttpStatusCode.NotFound -> null // Nothing released yet.
        else -> throw OfflineError(null)
      }
    }
    prefs.edit().putString(PREF_RELEASE, json).putLong(PREF_RELEASE_AT, now).apply()
  } catch (e: Exception) {
    if (force) throw networkCode(e)?.let(::OfflineError) ?: e
  }
  return json?.let { newerRelease(it, BuildConfig.VERSION_CODE.toLong()) }.also { Updates.available.value = it }
}

/**
 * Downloads [release]'s APK into [file], [onPercent] as it goes; a SHA-256 mismatch deletes it: [OfflineError] "checksum".
 * Earlier updates' APKs next to it go.
 */
suspend fun downloadApk(release: Release, file: File, onPercent: (Int) -> Unit = {}) {
  file.parentFile?.mkdirs()
  file.parentFile?.listFiles()?.filter { it != file }?.forEach { it.delete() }
  val digest = MessageDigest.getInstance("SHA-256")
  try {
    storage.prepareGet(release.url).execute { response ->
      if (response.status != HttpStatusCode.OK) throw OfflineError(null)
      val total = (response.contentLength() ?: 0).coerceAtLeast(1)
      var done = 0L
      val input = response.bodyAsChannel()
      file.outputStream().use { output ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val n = input.readAvailable(buf).takeIf { it >= 0 } ?: break
          output.write(buf, 0, n)
          digest.update(buf, 0, n)
          val before = done * 100 / total
          done += n
          if (done * 100 / total != before) onPercent((done * 100 / total).toInt().coerceAtMost(100))
        }
      }
    }
  } catch (e: Exception) {
    file.delete()
    throw networkCode(e)?.let(::OfflineError) ?: e
  }
  if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha256, ignoreCase = true)) {
    file.delete()
    throw OfflineError("checksum")
  }
}

/**
 * Where [downloadApk] puts [release] (res/xml/file_paths.xml shares it with the installer). One name per version: the
 * same URI again would bring back the installer still open from the last update, with that update's APK in it.
 */
fun updateFile(context: Context, release: Release) = File(context.cacheDir, "updates/stars-trail-${release.name}.apk")

/** The system installer for a [downloadApk]ed APK; the first time, it asks to allow installs from this app itself. */
fun installApk(context: Context, file: File) = context.startActivity(
  Intent(Intent.ACTION_VIEW)
    .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.files", file), "application/vnd.android.package-archive")
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
)
