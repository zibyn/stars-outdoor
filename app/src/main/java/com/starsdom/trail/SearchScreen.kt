package com.starsdom.trail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 顶部搜索框 → 搜索 (整页, §8.2 第 1、2 条): places and coordinates, each with its kind's icon, 区县 and the way there
 * from [from] (me, else the map's centre). [offline]: those from this phone's 地名索引. [busy]: the online search
 * running, a spinner in the field after 300 ms. [note]: why nothing shows. Empty, only the placeholder.
 */
@Composable
fun SearchScreen(
  query: String,
  results: List<Place>,
  offline: Set<Place>,
  from: Pair<Double, Double>,
  busy: Boolean,
  note: String?,
  onQuery: (String) -> Unit,
  onPick: (Place) -> Unit,
  online: Boolean,
) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Page(Modifier.padding(Space.L)) {
    BasicTextField(
      query, onQuery,
      Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp),
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onPick) }),
      decorationBox = { field ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            field()
          }
          if (busy) Spinner(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      },
    )
    OfflineStatus(online)
    note?.let { Text(it, Modifier.padding(top = Space.M), MaterialTheme.colorScheme.onSurfaceVariant) }
    LazyColumn(Modifier.weight(1f)) {
      items(results) { p -> ResultRow(p, p in offline, from) { onPick(p) } }
    }
  }
}

/** C2-07…10: the kind's icon, name, 区县, and 「↙ 42 km」. */
@Composable
private fun ResultRow(p: Place, offline: Boolean, from: Pair<Double, Double>, onClick: () -> Unit) = Row(
  Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = Space.XS).semantics(mergeDescendants = true) {},
  verticalAlignment = Alignment.CenterVertically,
) {
  val category = placeCategory(p.kind)
  Icon(category.icon, category.label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  Column(Modifier.weight(1f).padding(horizontal = Space.M)) {
    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    resultLine(p, offline)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
  }
  Text(wayText(from.first, from.second, p.lat, p.lon), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
