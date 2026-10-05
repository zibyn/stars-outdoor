import java.util.Properties

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
    versionName = "0.1.0"
    // The API (server/). Before launch it's the LAN test server (ADR 0003): put
    // The API (deploy/README.md). Another server, e.g. one on this machine from the emulator: -PstarsApiUrl=http://10.0.2.2:8080.
    buildConfigField("String", "API_URL", "\"${providers.gradleProperty("starsApiUrl").getOrElse("https://outdoor.starsdom.com:9443")}\"")
  }
  buildFeatures { buildConfig = true }
  // Release signing, configured by scripts/build-apk.sh; without keystore.properties assembleRelease is unsigned.
  val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().also { p -> f.inputStream().use(p::load) } }
  if (keystore != null) {
    signingConfigs.create("release") {
      storeFile = rootProject.file(keystore.getProperty("storeFile"))
      storePassword = keystore.getProperty("storePassword")
      keyAlias = keystore.getProperty("keyAlias")
      keyPassword = keystore.getProperty("keyPassword")
    }
    buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
  }
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
  // 启动画面 (ux-v3 §8.1): Android 12's, backported to 26.
  implementation("androidx.core:core-splashscreen:1.2.0")
  implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
  // Pinned: alpha28 needs foundation 1.13.0-alpha01 (ux-v3 §2.1).
  implementation("androidx.compose.material3:material3:1.5.0-alpha27")
  implementation("com.garmin:fit:21.217.0")
  // 队伍 WebSocket (§2.11); Android has no WebSocket client of its own.
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
  // TrackDb's SQL against a real SQLite.
  testImplementation("org.robolectric:robolectric:4.16")
}
