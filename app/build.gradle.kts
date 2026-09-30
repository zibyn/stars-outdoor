plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.starsdom.outdoor"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.starsdom.outdoor"
    minSdk = 26
    targetSdk = 36
    // Sent as X-Client-Version; the server answers 426 below its MIN_CLIENT_VERSION.
    versionCode = 1
    // The API (server/). Before launch it's the LAN test server (ADR 0003): put
    // The API (deploy/README.md). Another server, e.g. one on this machine from the emulator: -PstarsApiUrl=http://10.0.2.2:8080.
    buildConfigField("String", "API_URL", "\"${providers.gradleProperty("starsApiUrl").getOrElse("https://outdoor.starsdom.com:9443")}\"")
  }
  buildFeatures { buildConfig = true }
}

// Glyphs (CJK Noto Sans, ~34 MB) ship in the APK; a missing glyph range silently blanks whole sources.
tasks.named("preBuild") {
  doFirst {
    val ranges = file("src/main/assets/fonts/Noto Sans Regular").listFiles().orEmpty().count { it.length() > 0 }
    require(ranges == 256) { "Glyphs incomplete ($ranges/256 ranges): run scripts/fetch-glyphs.sh" }
  }
}

dependencies {
  implementation("org.maplibre.compose:maplibre-compose:0.18.0")
  runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.18.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
  implementation("com.garmin:fit:21.217.0")
  // 队伍 WebSocket (§2.11); Android has no WebSocket client of its own.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
  // TrackDb's SQL against a real SQLite.
  testImplementation("org.robolectric:robolectric:4.16")
}
