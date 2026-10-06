import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  // 整页 keys (nav/), saved with the back stack.
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.starsdom.trail"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.starsdom.trail"
    minSdk = 26
    targetSdk = 36
    // From the newest v* tag (#51): v1.2.3 → 1.2.3 and versionCode 10203 (Update.kt's versionCodeOf), no tag 0.1.0.
    // versionCode is sent as X-Client-Version; the server answers 426 below its MIN_CLIENT_VERSION.
    val tag = providers.exec { commandLine("git", "describe", "--tags", "--match", "v*", "--abbrev=0"); isIgnoreExitValue = true }
      .standardOutput.asText.get().trim().ifEmpty { "v0.1.0" }
    val (major, minor, patch) = Regex("""v(\d+)\.(\d+)\.(\d+)""").matchEntire(tag)!!.destructured
    versionCode = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
    versionName = "$major.$minor.$patch"
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
  // The API client, generated from server/openapi.yaml (ADR 0016).
  implementation(project(":shared"))
  implementation("org.maplibre.compose:maplibre-compose:0.18.0")
  runtimeOnly("org.maplibre.compose:maplibre-compose-runtime-opengl-android:0.18.0")
  // 1.12: what Navigation 3 needs; Back goes through NavigationEvent, the handler added last first.
  implementation("androidx.activity:activity-compose:1.12.0")
  // 整页 over the map (ADR 0015).
  implementation("androidx.navigation3:navigation3-ui:1.2.0")
  // 启动画面 (ux-v3 §8.1): Android 12's, backported to 26.
  implementation("androidx.core:core-splashscreen:1.2.0")
  implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
  // Pinned: alpha28 needs foundation 1.13.0-alpha01 (ux-v3 §2.1).
  implementation("androidx.compose.material3:material3:1.5.0-alpha27")
  implementation("com.garmin:fit:21.217.0")
  testImplementation("junit:junit:4.13.2")
  // TrackDb's SQL against a real SQLite.
  testImplementation("org.robolectric:robolectric:4.16")
  // The 轨迹库's flows and its 撤销 window, in virtual time; the coroutines version compose brings in.
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
  // The API over a fake engine (package downloads).
  testImplementation("io.ktor:ktor-client-mock:3.6.0")
  // HttpTeamTransport against the memory transport's rules, served over HTTP and WebSocket in-process.
  testImplementation("io.ktor:ktor-server-test-host:3.6.0")
  testImplementation("io.ktor:ktor-server-websockets:3.6.0")
}
