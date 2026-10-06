package com.starsdom.trail

/** Small settings kept across launches: SharedPreferences on Android ([SharedPrefs]). */
interface Prefs {
  fun getLong(key: String, default: Long): Long
  fun getBoolean(key: String, default: Boolean): Boolean
  fun getString(key: String, default: String?): String?
  /** Writes these together: a Long, Boolean or String, or null to remove the key. */
  fun put(vararg values: Pair<String, Any?>)
}
