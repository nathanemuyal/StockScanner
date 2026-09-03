package com.warehouse.stockscanner.data

import android.content.Context

/**
 * Small persisted UI state that is not part of the product table itself:
 * which file is loaded, which location is currently active, and how many
 * products were approved so far. Backed by SharedPreferences so it survives
 * the app being closed and reopened.
 */
class SessionPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("stock_scanner_prefs", Context.MODE_PRIVATE)

    var fileName: String?
        get() = prefs.getString(KEY_FILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_FILE_NAME, value).apply()

    var fileUri: String?
        get() = prefs.getString(KEY_FILE_URI, null)
        set(value) = prefs.edit().putString(KEY_FILE_URI, value).apply()

    var currentLocation: String?
        get() = prefs.getString(KEY_CURRENT_LOCATION, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_LOCATION, value).apply()

    var approvedCount: Int
        get() = prefs.getInt(KEY_APPROVED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_APPROVED_COUNT, value).apply()

    /** Called whenever a brand-new Excel file is loaded, replacing any previous session. */
    fun resetForNewFile(fileName: String, uri: String) {
        prefs.edit()
            .putString(KEY_FILE_NAME, fileName)
            .putString(KEY_FILE_URI, uri)
            .putString(KEY_CURRENT_LOCATION, null)
            .putInt(KEY_APPROVED_COUNT, 0)
            .apply()
    }

    companion object {
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_FILE_URI = "file_uri"
        private const val KEY_CURRENT_LOCATION = "current_location"
        private const val KEY_APPROVED_COUNT = "approved_count"
    }
}
