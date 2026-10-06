package com.starsdom.trail

// 登录 (spec §2.12): +86 numbers only, by a texted code. Asked for only by 队伍 and 开启同步; logged out,
// everything else works and data stays on the phone.

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.starsdom.trail.account.Account
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SharedPreferences: the account's 昵称 as last heard from the server (ux-v3 §8.4 第 5 条). */
const val PREF_NICKNAME = "nickname"

const val MAX_NICKNAME = 12

/** Characters as the server counts them: an emoji is one. */
fun nicknameLength(s: String) = s.codePointCount(0, s.length)

/** [draft] trimmed, or null if that's empty or over [MAX_NICKNAME] (the server's rule, nickname.go). */
fun nicknameOf(draft: String): String? = draft.trim().takeIf { it.isNotEmpty() && nicknameLength(it) <= MAX_NICKNAME }

/** The 首字 a dot or an avatar shows (ux-v3 §8.4 第 7 条): the first character whole, an emoji included. */
fun initial(name: String) = if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0)))

/** 「+86 138****8000」 (C6-39). */
fun maskedPhone(phone: String) = "+86 " + phone.take(3) + "****" + phone.takeLast(4)

/** [text] as the 11-digit number the server takes (spaces, dashes and +86 dropped), or null if it's not a mainland mobile. */
fun mainlandPhone(text: String): String? {
  val digits = text.filterNot { it == ' ' || it == '-' }.removePrefix("+86").let { if (it.length == 13) it.removePrefix("86") else it }
  return digits.takeIf { Regex("1[3-9][0-9]{9}").matches(it) }
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
