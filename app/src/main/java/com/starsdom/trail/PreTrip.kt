package com.starsdom.trail

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.starsdom.trail.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 出发前检查 (ux-v3 §8.3 第 1–5 条): what the phone itself says, read back each time; the brands' own autostart
// settings can't be read, so they aren't one.

/**
 * The five, in the drawer's order (C6-15), each with its [label], [icon], the button that fixes it (C6-16) and, for
 * the three that don't stop a recording, its reminder (C3-04…07). Without the first two nothing can be recorded.
 */
enum class Check(@StringRes val label: Int, @DrawableRes val icon: Int, @StringRes val action: Int, @StringRes val remind: Int = 0) {
  Precise(R.string.check_precise, R.drawable.location_on_wght500_24px, R.string.action_open_settings),
  LocationOn(R.string.check_location_on, R.drawable.location_off_wght500_24px, R.string.action_open_location),
  Notifications(R.string.check_notifications, R.drawable.notifications_wght500_24px, R.string.action_open_settings, R.string.remind_notifications),
  Battery(R.string.check_battery, R.drawable.battery_alert_wght500_24px, R.string.action_turn_off, R.string.remind_battery),
  Offline(R.string.check_offline, R.drawable.map_wght500_24px, R.string.action_download, R.string.remind_offline),
}

/**
 * The phone as it is: location permission [precise] (approximate isn't enough), the system location switch, whether
 * notifications show, battery optimisation off for us, and whether the 参考轨迹 (or, without one, where I am) is in
 * an offline package — offline, there's nothing to download, so that one passes.
 */
data class PhoneState(val precise: Boolean, val locationOn: Boolean, val notifications: Boolean, val batteryUnrestricted: Boolean, val offlineCovered: Boolean)

fun failing(s: PhoneState): Set<Check> = buildSet {
  if (!s.precise) add(Check.Precise)
  if (!s.locationOn) add(Check.LocationOn)
  if (!s.notifications) add(Check.Notifications)
  if (!s.batteryUnrestricted) add(Check.Battery)
  if (!s.offlineCovered) add(Check.Offline)
}

/** Skipped this many times in a row, it's no longer brought up on starting (§8.3 第 4 条). */
const val SKIPS_ENOUGH = 3

/**
 * What to bring up once recording starts (第 3、4 条), one 提示条 at a time: battery, offline, notifications; not
 * one [skips] [SKIPS_ENOUGH] times in a row, and offline only [online] (it'd download).
 */
fun reminders(failing: Set<Check>, skips: (Check) -> Int, online: Boolean): List<Check> =
  listOf(Check.Battery, Check.Offline, Check.Notifications).filter { it in failing && skips(it) < SKIPS_ENOUGH && (it != Check.Offline || online) }

/** Whether ([lat], [lon]) is in a package made from one of [requests]: inside its box, or within its track's 2 km corridor. */
fun covered(lat: Double, lon: Double, requests: List<String>): Boolean = requests.any { r ->
  val o = runCatching { Json.parseToJsonElement(r).jsonObject }.getOrNull() ?: return@any false
  o["bbox"]?.jsonArray?.map { it.jsonPrimitive.double }?.let { (w, s, e, n) -> return@any lon in w..e && lat in s..n }
  val points = o["track"]?.jsonArray?.map { p -> p.jsonArray.let { TrackPoint(0, it[1].jsonPrimitive.double, it[0].jsonPrimitive.double, null) } } ?: return@any false
  points.isNotEmpty() && alongTrack(lat, lon, listOf(points)).offM <= 2_000
}

/** Brands whose own battery savers stop background apps, whatever Android's optimisation says (C6-17). */
// ponytail: by maker name; add one when its users report recordings stopping.
fun brandNote(manufacturer: String): Boolean =
  manufacturer.lowercase() in setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "iqoo")

/**
 * The 出发前检查 小抽屉 (C6-15…17): the five, each ✓ or its button ([onFix]); under 电池优化, on the [brand]s that need it,
 * a line and 去开启 ([onAppSettings]).
 */
@Composable
fun PreTripSheet(notRight: Set<Check>, brand: Boolean, onFix: (Check) -> Unit, onAppSettings: () -> Unit, onClose: () -> Unit, modifier: Modifier) =
  ActionSheet(stringResource(R.string.pretrip), modifier, onClose) {
    for (c in Check.entries) {
      Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(c.label), Modifier.weight(1f))
        if (c in notRight) TextButton({ onFix(c) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(c.action)) }
        else Text("✓", Modifier.padding(horizontal = Space.M), MaterialTheme.colorScheme.primary)
      }
      if (c == Check.Battery && brand) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.check_brand), Modifier.weight(1f), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        TextButton(onAppSettings, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_open_settings)) }
      }
    }
  }

/** 轨迹详情's row on the 参考轨迹 (C2-61): 「出发前检查」, the icons of what isn't right, ›; none when all is. */
@Composable
fun PreTripRow(notRight: Set<Check>, onClick: () -> Unit) {
  if (notRight.isEmpty()) return
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(stringResource(R.string.pretrip) + " ·", Modifier.padding(end = Space.XS))
    for (c in Check.entries.filter { it in notRight }) Icon(c.icon, stringResource(c.label), Modifier.padding(end = Space.XXS), MaterialTheme.colorScheme.error, 20.dp)
    Spacer(Modifier.weight(1f))
    Icon(R.drawable.chevron_right_wght500_24px, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

