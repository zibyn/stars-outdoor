package dev.stars.outdoor

// 叠加 (ux-v2 §9.2): my tracks drawn on the map at once, each in its palette colour. Kept on this phone only.

/** Track id → palette index, as "id:index,…". */
const val PREF_OVERLAYS = "overlays"

// ponytail: picked to stay clear of 记录红, 参考蓝 and memberColor on paper; tune on a real phone.
/** ux-v2 §3.8 叠加调色板: one colour per overlaid track, so at most this many. */
val overlayColors = listOf(0xFF283593, 0xFF7CB342, 0xFF00695C, 0xFF9E9D24, 0xFFAD1457, 0xFFEF6C00)

/** [id] overlaid in the smallest free colour; already there, unchanged; null once all colours are taken. */
fun Map<Long, Int>.overlay(id: Long): Map<Long, Int>? = when {
  id in this -> this
  size >= overlayColors.size -> null
  else -> this + (id to overlayColors.indices.first { it !in values })
}

fun overlaysText(m: Map<Long, Int>) = m.entries.joinToString(",") { "${it.key}:${it.value}" }

/** The stored set, less tracks no longer in [tracks]. */
fun readOverlays(text: String?, tracks: Set<Long>): Map<Long, Int> =
  text.orEmpty().split(',').mapNotNull { e ->
    val (id, color) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
    (id.toLongOrNull() ?: return@mapNotNull null) to (color.toIntOrNull()?.takeIf { it in overlayColors.indices } ?: return@mapNotNull null)
  }.filter { it.first in tracks }.toMap()
