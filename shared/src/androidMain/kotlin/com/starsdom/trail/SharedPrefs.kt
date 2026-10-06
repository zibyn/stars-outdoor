package com.starsdom.trail

import android.content.SharedPreferences

class SharedPrefs(private val prefs: SharedPreferences) : Prefs {
  override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
  override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
  override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
  override fun put(vararg values: Pair<String, Any?>) = prefs.edit().apply {
    for ((key, value) in values) when (value) {
      null -> remove(key)
      is Long -> putLong(key, value)
      is Boolean -> putBoolean(key, value)
      is String -> putString(key, value)
      else -> error("$key: ${value::class}")
    }
  }.apply()
}
