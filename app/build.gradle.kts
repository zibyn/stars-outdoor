plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "dev.stars.outdoor"
  compileSdk = 37
  defaultConfig {
    applicationId = "dev.stars.outdoor"
    minSdk = 26
    targetSdk = 36
    // Sent as X-Client-Version; the server answers 426 below its MIN_CLIENT_VERSION.
    versionCode = 1
    // The API (server/). Before launch it's the LAN test server (ADR 0003): put
    // starsApiUrl=http://<server LAN address>:8080 in ~/.gradle/gradle.properties. Default: the host, from the emulator.
    buildConfigField("String", "API_URL", "\"${providers.gradleProperty("starsApiUrl").getOrElse("http://10.0.2.2:8080")}\"")
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
  testImplementation("junit:junit:4.13.2")
}
