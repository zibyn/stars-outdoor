// PROTOTYPE — throwaway spike: MapLibre Compose + local PMTiles offline rendering.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "offline-spike"
include(":app")
