plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "dev.stars.spike"
  compileSdk = 37
  defaultConfig {
    applicationId = "dev.stars.spike"
    minSdk = 26
    targetSdk = 36
  }
}

dependencies {
  implementation("org.maplibre.compose:maplibre-compose:0.18.0")
  runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.18.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
}
