package com.starsdom.outdoor

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// 出错 (ux-v3 §7.1, R9): 「{结果} · {原因}」, the 原因 one of a few fixed ones.

/** CS-07: the server's error code (or "offline", [OfflineError]) → the 原因 the user sees; unknown ones (and "timeout") are the server's. */
@StringRes
fun reasonOf(code: String?): Int = when (code) {
  "offline" -> R.string.reason_offline
  "image_not_found" -> R.string.reason_image
  "invalid_region", "region_too_large" -> R.string.reason_too_large
  "region_unsupported" -> R.string.reason_unsupported
  "team_not_found" -> R.string.reason_no_team
  "not_initiator" -> R.string.reason_not_initiator
  "team_ended" -> R.string.reason_team_ended
  "unauthorized" -> R.string.reason_logged_out
  "invalid_phone" -> R.string.reason_phone
  "wrong_code" -> R.string.reason_code
  "sms_too_frequent" -> R.string.reason_sms_frequent
  "sms_unavailable" -> R.string.reason_sms_unavailable
  "rate_limited" -> R.string.reason_rate_limited
  else -> R.string.reason_server
}

/** The code of a failed call ([OfflineError]): the server's, "offline", "timeout", or null for anything else. */
val Throwable.errorCode get() = (this as? OfflineError)?.code

/** Whether [e] (or what caused it) is the phone out of space (R9: 手机空间不足). */
// ponytail: told by the message, as Java's IOException carries no errno; check ErrnoException causes if one slips by.
fun noSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { (it.message ?: "").contains("ENOSPC") || (it.message ?: "").contains("No space left") }

/** In the page, by what went wrong (no ⚠: that's the 提示条's): 「退出失败 · 没有网络」. */
fun Context.errorText(@StringRes result: Int, code: String?) = getString(R.string.error_with_reason, getString(result), getString(reasonOf(code)))

/** A page's 出错 line, under what went wrong, with 重试 when it can be (§7.1). */
@Composable
fun PageError(text: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
  Text(text, Modifier.weight(1f), MaterialTheme.colorScheme.error)
  onRetry?.let {
    Text(
      stringResource(R.string.action_retry),
      Modifier.heightIn(min = 48.dp).clickable(onClick = it).padding(start = Space.M).wrapContentHeight(),
      MaterialTheme.colorScheme.primary,
    )
  }
}

/** A failed 提示条: 「⚠ {结果} · {原因}」, with 重试 when [retry] is given. */
fun Context.failHint(@StringRes result: Int, @StringRes reason: Int, retry: (() -> Unit)? = null) =
  Hint(getString(R.string.failed, getString(result), getString(reason)), listOfNotNull(retry?.let { getString(R.string.action_retry) to it }))

/** A failed 提示条 with no 原因 to give: 「⚠ {动作}没成功」 (R9), with 重试 when [retry] is given. */
fun Context.failHint(@StringRes what: Int, retry: (() -> Unit)? = null) =
  Hint(getString(R.string.failed_plain, getString(what)), listOfNotNull(retry?.let { getString(R.string.action_retry) to it }))
