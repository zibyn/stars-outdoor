package dev.stars.outdoor

// 登录 (spec §2.12): +86 numbers only, by a texted code. Asked for only by 队伍 and 开启同步; logged out,
// everything else works and data stays on the phone.

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The logged-in account: its number, and the server's bearer token. */
data class Account(val phone: String, val token: String)

/** [text] as the 11-digit number the server takes (spaces, dashes and +86 dropped), or null if it's not a mainland mobile. */
fun mainlandPhone(text: String): String? {
  val digits = text.filterNot { it == ' ' || it == '-' }.removePrefix("+86").let { if (it.length == 13) it.removePrefix("86") else it }
  return digits.takeIf { Regex("1[3-9][0-9]{9}").matches(it) }
}

/** Server error code → what the user sees on the login screen. */
fun loginMessage(code: String?): String = when (code) {
  "invalid_phone" -> "请输入中国大陆手机号（仅支持 +86）"
  "wrong_code" -> "验证码不对或已过期"
  "sms_too_frequent" -> "验证码要得太频繁，1 分钟后再获取；今天的次数用完了就明天再来"
  "sms_unavailable" -> "短信暂时发不出去，过几分钟再获取验证码"
  "rate_limited" -> "试得太多次，1 小时后再登录"
  "offline" -> "没有网络，联网后再登录"
  else -> "登录没成功，再试一次"
}

/**
 * The [Account], kept in [prefs] encrypted with an AES key that never leaves the Android Keystore. A backup
 * restored onto another phone can't be decrypted there, so it reads as logged out.
 */
class AccountStore(private val prefs: SharedPreferences) {
  fun get(): Account? = prefs.getString(PREF, null)?.let { stored ->
    runCatching {
      val bytes = Base64.decode(stored, Base64.NO_WRAP)
      val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
      val (phone, token) = String(cipher.doFinal(bytes, 12, bytes.size - 12)).split('\n')
      Account(phone, token)
    }.getOrNull()
  }

  fun set(account: Account?) {
    if (account == null) return prefs.edit().remove(PREF).apply()
    val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    val sealed = cipher.iv + cipher.doFinal("${account.phone}\n${account.token}".toByteArray())
    prefs.edit().putString(PREF, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
  }

  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
  }

  private companion object {
    const val PREF = "account"
    const val ALIAS = "account"
    const val AES_GCM = "AES/GCM/NoPadding"
  }
}
