package com.starsdom.trail.offline

import com.starsdom.trail.OfflineError
import com.starsdom.trail.net.trailClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// 下载离线包 (§2.3): the API's answer names the files; each comes from its signed URL, written as it comes.
class PackageDownloadTest {
  private val dir = File(Files.createTempDirectory("pkg").toFile(), "staging")
  private val basemap = ByteArray(300_000) { it.toByte() }
  private val asked = mutableListOf<String>()

  private fun answer(bytes: Long = basemap.size.toLong()) =
    """{"version":"7","bytes":${basemap.size + 2},"outline":{"type":"Polygon","coordinates":[[[108,34],[109,34],[109,35],[108,34]]]},
      "files":[{"name":"basemap.pmtiles","url":"https://store/b?sig=1","bytes":$bytes},{"name":"places.sqlite","url":"https://store/p?sig=1","bytes":2}]}"""

  private val api = trailClient("http://api", "device", 5, MockEngine { req ->
    asked += req.url.toString()
    respond(answer(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
  }) {}

  private fun storage() = HttpClient(MockEngine { req ->
    asked += req.url.toString()
    assertEquals(null, req.headers["X-Device-Id"])
    when (req.url.encodedPath) {
      "/b" -> respond(basemap, HttpStatusCode.OK)
      else -> respond(byteArrayOf(1, 2), HttpStatusCode.OK)
    }
  })

  @Test fun theFilesComeFromTheirUrlsWithProgress() = runTest {
    val percents = mutableListOf<Int>()
    val pkg = fetchPackage(api, storage(), "太白山附近", bboxRequest(107.7, 33.9, 107.8, 34.0), dir) { percents += it }
    assertEquals(listOf("http://api/v1/offline/packages", "https://store/b?sig=1", "https://store/p?sig=1"), asked)
    assertTrue(basemap.contentEquals(File(dir, "basemap.pmtiles").readBytes()))
    assertEquals(2L, File(dir, "places.sqlite").length())
    assertEquals(100, percents.last())
    assertTrue(percents.size > 2)
    assertEquals(OfflinePackage(dir, "太白山附近", "7", bboxRequest(107.7, 33.9, 107.8, 34.0), basemap.size + 2L, pkg.outline), pkg)
    assertEquals(pkg, readPackage(dir))
    assertEquals(listOf(108.0, 34.0, 109.0, 35.0), outlineBox(pkg.outline!!))
  }

  @Test fun aFileOfTheWrongSizeFails() = runTest {
    val short = trailClient("http://api", "device", 5, MockEngine {
      respond(answer(bytes = basemap.size + 1L), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }) {}
    try {
      fetchPackage(short, storage(), "x", bboxRequest(0.0, 0.0, 1.0, 1.0), dir)
      fail()
    } catch (e: OfflineError) {
      assertEquals(null, e.code)
    }
  }

  @Test fun aRefusedFileFails() = runTest {
    try {
      fetchPackage(api, HttpClient(MockEngine { respond("expired", HttpStatusCode.Forbidden) }), "x", bboxRequest(0.0, 0.0, 1.0, 1.0), dir)
      fail()
    } catch (e: OfflineError) {
      assertEquals(null, e.code)
    }
  }
}
