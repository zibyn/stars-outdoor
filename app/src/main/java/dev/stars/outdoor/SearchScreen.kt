package dev.stars.outdoor

import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt

/** 菜单 → 搜索 (§2.10): places and coordinates; [note] says where the results came from, or why not online. */
@Composable
fun SearchScreen(query: String, results: List<Place>, note: String?, onQuery: (String) -> Unit, onPick: (Place) -> Unit) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(16.dp)) {
    BasicTextField(
      query, onQuery,
      Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).padding(12.dp),
      textStyle = TextStyle(fontSize = 16.sp),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onPick) }),
      decorationBox = { field -> if (query.isEmpty()) BasicText("地名、山峰、景点或坐标", style = TextStyle(color = Color.Gray, fontSize = 16.sp)); field() },
    )
    note?.let { BasicText(it, Modifier.padding(top = 8.dp), style = TextStyle(color = Color.Gray, fontSize = 12.sp)) }
    LazyColumn(Modifier.weight(1f)) {
      items(results) { p ->
        Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 10.dp)) {
          BasicText(p.name)
          p.detail?.let { BasicText(it, style = TextStyle(color = Color.Gray, fontSize = 12.sp)) }
        }
      }
    }
  }
}

/** Places whose name (or 中文名, English name) contains [query], from each 地名索引 in [files] that exists. */
// ponytail: LIKE scans the whole table (issue #18: ~45 ms for all of China on a desktop); FTS5 trigram if phones lag.
fun searchPlaces(files: List<File>, query: String): List<Place> = files.filter { it.isFile }.flatMap { f ->
  runCatching {
    SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
      val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
      val sql = """SELECT name, name_zh, name_en, kind, lat, lon, ele, importance, detail FROM places
        WHERE name LIKE ?1 ESCAPE '\' OR name_zh LIKE ?1 ESCAPE '\' OR name_en LIKE ?1 ESCAPE '\'
        ORDER BY name = ?2 OR name_zh = ?2 DESC, importance DESC LIMIT 200"""
      db.rawQuery(sql, arrayOf(like, query)).use { c ->
        buildList {
          while (c.moveToNext()) {
            val names = listOfNotNull(c.getString(0), c.getString(1), c.getString(2))
            val ele = if (c.isNull(6)) null else "${c.getDouble(6).roundToInt()} m"
            val detail = listOfNotNull(ele, c.getString(8)).joinToString(" · ").ifEmpty { null }
            add(Place(names[0], c.getString(3), c.getDouble(4), c.getDouble(5), detail, c.getDouble(7), names))
          }
        }
      }
    }
  }.getOrDefault(emptyList())
}
