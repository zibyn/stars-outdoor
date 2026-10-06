package com.starsdom.trail.ui

import com.starsdom.trail.OfflineError
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// #51: 应用内更新 from GitHub Release, checked against this build's versionCode, verified by SHA-256 before installing.
class UpdateTest {
  private val apk = "not really an apk".toByteArray()
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/a.apk") { it.sendResponseHeaders(200, apk.size.toLong()); it.responseBody.use { o -> o.write(apk) } }
    start()
  }
  private val url = "http://127.0.0.1:${server.address.port}/a.apk"
  private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
  private val dir = kotlin.io.path.createTempDirectory("updates").toFile()
  private val file = File(dir, "stars-trail-1.2.0.apk")

  @After fun stop() { server.stop(0); dir.deleteRecursively() }

  private fun latest(tag: String, assets: String = """[{"name":"stars-trail-abc.apk","browser_download_url":"$url","digest":"sha256:$sha"}]""") =
    """{"tag_name":"$tag","assets":$assets}"""

  @Test fun aTagIsAVersionCode() {
    assertEquals(10203L, versionCodeOf("v1.2.3"))
    assertEquals(100L, versionCodeOf("v0.1.0"))
    assertNull(versionCodeOf("nightly"))
  }

  @Test fun onlyANewerReleaseIsAnUpdate() {
    assertEquals(Release("1.2.0", url, sha), newerRelease(latest("v1.2.0"), 10100))
    assertNull(newerRelease(latest("v1.2.0"), 10200))
  }

  @Test fun aReleaseWithoutAVerifiableApkIsNoUpdate() {
    assertNull(newerRelease(latest("v1.2.0", "[]"), 1))
    assertNull(newerRelease(latest("v1.2.0", """[{"name":"a.apk","browser_download_url":"$url","digest":null}]"""), 1))
  }

  @Test fun theApkIsKeptWhenItsSha256Matches() = runTest {
    downloadApk(Release("1.2.0", url, sha), file)
    assertTrue(file.readBytes().contentEquals(apk))
  }

  @Test fun aMismatchedApkIsRefusedAndDeleted() = runTest {
    val e = runCatching { downloadApk(Release("1.2.0", url, "0".repeat(64)), file) }.exceptionOrNull()
    assertEquals("checksum", (e as OfflineError).code)
    assertFalse(file.exists())
  }

  // Each version has its own file, so its own URI for the installer; the last update's APK goes.
  @Test fun anEarlierUpdatesApkIsDeleted() = runTest {
    val earlier = File(dir, "stars-trail-1.1.0.apk").apply { writeText("old") }
    downloadApk(Release("1.2.0", url, sha), file)
    assertFalse(earlier.exists())
    assertTrue(file.exists())
  }
}
