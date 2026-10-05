package com.starsdom.outdoor

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** 顶部搜索框 → 搜索 (整页, §2.10): places and coordinates; [note] says where the results came from, or why not online. */
@Composable
fun SearchScreen(query: String, results: List<Place>, note: String?, onQuery: (String) -> Unit, onPick: (Place) -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Page(Modifier.padding(16.dp)) {
    BasicTextField(
      query, onQuery,
      Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(12.dp),
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onPick) }),
      decorationBox = { field -> if (query.isEmpty()) Text("地名、山峰、景点或坐标", color = MaterialTheme.colorScheme.onSurfaceVariant); field() },
    )
    note?.let { Text(it, Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
    LazyColumn(Modifier.weight(1f)) {
      items(results) { p ->
        Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)) {
          Text(p.name)
          p.detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
        }
      }
    }
  }
}
