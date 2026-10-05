package com.starsdom.outdoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 设置 → 关于 (二级页; ux-v3 §8.6 第 11 条, C6-51, C6-52): 「星径」 and its 版本 in big type, then 数据来源 ›. */
@Composable
fun AboutScreen(onBack: () -> Unit, onSources: () -> Unit) = Page(Modifier.padding(horizontal = Space.L)) {
  BackTitle(stringResource(R.string.about), onBack)
  Text(stringResource(R.string.brand), Modifier.padding(top = Space.XL), style = MaterialTheme.typography.headlineMedium)
  Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), Modifier.padding(bottom = Space.XL), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onSources), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.data_sources), Modifier.weight(1f))
    Icon(R.drawable.chevron_right_wght500_24px, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** C6-53: each row's link (null: none), in the order of `sources_what` / `sources_whose`. */
private val SOURCE_LINKS = listOf(
  "https://www.openstreetmap.org/copyright",
  "https://protomaps.com",
  null,
  "https://mapterhorn.com",
  "https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM",
  "https://photon.komoot.io",
  "https://open-meteo.com",
)

/**
 * 关于 → 数据来源 (二级页; §8.6 第 12 条): source and licence, one row each, then the OSM extraction script ([osmExtract],
 * ODbL). Licence attributions aren't held to the word limits (§7.2).
 */
@Composable
fun SourcesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, osmExtract: String) = Page(Modifier.padding(horizontal = Space.L)) {
  BackTitle(stringResource(R.string.data_sources), onBack)
  Column(Modifier.verticalScroll(rememberScrollState())) {
    val what = stringArrayResource(R.array.sources_what)
    val whose = stringArrayResource(R.array.sources_whose)
    for ((i, url) in SOURCE_LINKS.withIndex()) LinkRow(url?.let { { onOpen(it) } }) {
      Text(what[i], Modifier.weight(0.4f).padding(end = Space.M), MaterialTheme.colorScheme.onSurfaceVariant)
      Text(whose[i], Modifier.weight(0.6f))
    }
    LinkRow({ onOpen(osmExtract) }, Modifier.padding(top = Space.L)) {
      Text(stringResource(R.string.osm_extract), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
    }
  }
}

/** A row that opens a link ([onClick]; none: plain), the ↗ at its end saying it leaves the app. */
@Composable
private fun LinkRow(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) = Row(
  modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
  verticalAlignment = Alignment.CenterVertically,
) {
  content()
  // Same width with or without, so the columns line up.
  val label = stringResource(R.string.external_link)
  Text(if (onClick != null) "↗" else "", Modifier.width(24.dp).semantics { contentDescription = label }, MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
}

/** A 二级页's top: ← and its title (§5.5 ← 规则). */
@Composable
fun BackTitle(title: String, onBack: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
  DrawerIconButton(R.drawable.arrow_back_wght500_24px, stringResource(R.string.back), onBack)
  Text(title, Modifier.padding(start = Space.XS), style = MaterialTheme.typography.titleLarge)
}
