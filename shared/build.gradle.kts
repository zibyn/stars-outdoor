// Code the app shares with iOS (only compiled for it so far): the API client, generated from server/openapi.yaml at build time (ADR 0016), and the domain logic.
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
  // Linux compiles these but can't run their tests; CI's macOS job does both (ios.yml).
  iosArm64()
  iosSimulatorArm64()
  sourceSets {
    commonMain.dependencies {
      api("de.quati.ogen:client-ktor:0.13.1")
      implementation("de.quati.ogen:core:0.13.1")
      // OpenGen's Option, in the generated models; its JVM variant only has it at runtime.
      api("de.quati:kotlin-util:2.6.0")
      api("io.ktor:ktor-client-core:3.6.0")
      // Dates in the domain logic (time zones for 天气's days, clock times).
      api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
      implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
      implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
    androidMain.dependencies {
      // The app hands it to [trailClient].
      api("io.ktor:ktor-client-okhttp:3.6.0")
    }
    iosMain.dependencies {
      api("io.ktor:ktor-client-darwin:3.6.0")
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
