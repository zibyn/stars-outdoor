package com.starsdom.trail.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The 抽屉 (ADR 0015): one open at a time, the next one opened replacing it, and the 地点小抽屉 ([place]) over it. Off the
 * back stack: the map's, held by the activity (things off screen open them, an import) and saved with it.
 */
@Serializable data class Drawers(val open: Drawer? = null, val place: Pin? = null) {
  /** The track whose 轨迹详情 is in 我的轨迹, under whatever's over it. */
  val detail get() = tracks.filterIsInstance<TrackLayer.Detail>().lastOrNull()?.id
  /** The 标注 being edited in 我的轨迹. */
  val editing get() = (tracks.lastOrNull() as? TrackLayer.Waypoint)?.id
  /** 我的轨迹's layers, bottom first; none when it isn't open. */
  val tracks get() = (open as? Drawer.Tracks)?.layers.orEmpty()
}

/** A long-pressed point (or a search result) the 地点小抽屉 is for. */
@Serializable data class Pin(val lat: Double, val lon: Double)

@Serializable sealed interface Drawer {
  @Serializable data object Layers : Drawer
  /** 我的位置 tapped: 分享坐标 / 标注这里. */
  @Serializable data object Me : Drawer
  /** 周边路网's tracks near a tap; the tracks themselves aren't kept with it. */
  @Serializable data object Nearby : Drawer
  @Serializable data object Reference : Drawer
  @Serializable data class Mate(val id: Long) : Drawer
  /** 我的轨迹 (ux-v3 §5.5): list → 轨迹详情 → 标注组 → 标注 being edited, back going down a layer. */
  @Serializable data class Tracks(val layers: List<TrackLayer>) : Drawer
}

@Serializable sealed interface TrackLayer {
  @Serializable data object List : TrackLayer
  @Serializable data class Detail(val id: Long) : TrackLayer
  @Serializable data class Group(val id: Long) : TrackLayer
  @Serializable data class Waypoint(val id: Long) : TrackLayer
}

/** [drawer] open in place of the one open, the 地点小抽屉 too. */
fun Drawers.open(drawer: Drawer) = Drawers(drawer)

/** [drawer] closed, if it's the one open. */
fun Drawers.close(drawer: Drawer) = if (open == drawer) copy(open = null) else this

/** The drawer open closed, whichever; a 地点小抽屉 stays. */
fun Drawers.closeDrawer() = copy(open = null)

/** The 地点小抽屉 closed; the drawer under it stays. */
fun Drawers.closePlace() = copy(place = null)

/** 轨迹详情 of [id] on its own (an import, a recording ended, 周边路网): back closes the drawer. */
fun Drawers.openDetail(id: Long) = open(Drawer.Tracks(listOf(TrackLayer.Detail(id))))

/**
 * [layer] over 我的轨迹's top one (from the list, a 标注组…), in place of one of its kind (another 标注 edited);
 * 我的轨迹 opens with it alone if it wasn't.
 */
fun Drawers.push(layer: TrackLayer) = open(Drawer.Tracks(tracks.filterNot { it::class == layer::class } + layer))

/** [layer] gone from 我的轨迹 (its back, or what it showed deleted), and 我的轨迹 with it if it was all there was. */
fun Drawers.without(layer: TrackLayer): Drawers {
  val left = tracks - layer
  return if (open !is Drawer.Tracks) this else copy(open = Drawer.Tracks(left).takeIf { left.isNotEmpty() })
}

/** 轨迹详情 now on track [id] (a piece cut or tracks merged, opened in its place). */
fun Drawers.detailNowOn(id: Long) =
  if (detail == null) openDetail(id) else copy(open = Drawer.Tracks(tracks.map { if (it is TrackLayer.Detail) TrackLayer.Detail(id) else it }))

/**
 * The 地点小抽屉 at [place]. It closes the drawer open, but for a 轨迹详情, which stays under it with what's under it
 * (#202: closed, back still goes to the list); what was over the 轨迹详情 goes.
 */
fun Drawers.openPlace(place: Pin) = downToDetail().copy(place = place)

/** 我的轨迹 down to its 轨迹详情, what's over it gone (dragged down); closed if there's none, as any other drawer. */
fun Drawers.downToDetail(): Drawers {
  val at = tracks.indexOfLast { it is TrackLayer.Detail }
  return copy(open = Drawer.Tracks(tracks.take(at + 1)).takeIf { at >= 0 })
}

/**
 * Tracks [ids] come in by themselves (an import, a recording ended, #196): back to the map, every 整页 closing but 登录,
 * which they wait under; one opens in its 轨迹详情 (back closes it), more stay in the list.
 */
fun Drawers.cameIn(ids: List<Long>, pages: MutableList<NavKey>): Drawers {
  pages.closePages()
  return ids.singleOrNull()?.let(::openDetail) ?: open(Drawer.Tracks(listOf(TrackLayer.List)))
}

/** A tap on the map: the 地点小抽屉 goes, and 我的位置, a 队友's or 周边路网's drawer. */
fun Drawers.tapped() = Drawers(open.takeUnless { it == Drawer.Me || it == Drawer.Nearby || it is Drawer.Mate })

/** Back: the 地点小抽屉 first, then 我的轨迹 a layer at a time, else the drawer open. */
fun Drawers.back(): Drawers = when {
  place != null -> copy(place = null)
  open is Drawer.Tracks -> without(tracks.last())
  else -> copy(open = null)
}
