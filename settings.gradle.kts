pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Provisions the JDK 21 the daemon needs (gradle/gradle-daemon-jvm.properties; OpenGen, ADR 0016).
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "stars-trail"
include(":app", ":shared")
