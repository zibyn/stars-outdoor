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
  implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
  testImplementation("junit:junit:4.13.2")
}
