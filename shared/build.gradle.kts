// Code the app shares with iOS later; for now the API client, generated from server/openapi.yaml at build time (ADR 0016).
plugins {
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
  id("org.jetbrains.kotlin.plugin.serialization")
  id("de.quati.ogen")
}

kotlin {
  android {
    namespace = "com.starsdom.trail.shared"
    compileSdk = 37
    minSdk = 26
    withHostTest {}
  }
  sourceSets {
    commonMain.dependencies {
      api("de.quati.ogen:client-ktor:0.13.1")
      implementation("de.quati.ogen:core:0.13.1")
      // OpenGen's Option; its JVM variant only has it at runtime.
      implementation("de.quati:kotlin-util:2.6.0")
      api("io.ktor:ktor-client-core:3.6.0")
      implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
      implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    }
    androidMain.dependencies {
      // The app hands it to [trailClient].
      api("io.ktor:ktor-client-okhttp:3.6.0")
    }
    getByName("androidHostTest").dependencies {
      implementation(kotlin("test-junit"))
      implementation("io.ktor:ktor-client-mock:3.6.0")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    }
  }
}

ogen {
  utilPackageName("com.starsdom.trail.net.util")
  add(packageName = "com.starsdom.trail.net") {
    specFile(rootProject.file("server/openapi.yaml").path)
    model {}
    clientKtor {}
  }
}
