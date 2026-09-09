package com.warehouse.stockscanner.data

import android.content.Context

/**
 * Small persisted UI state that is not part of the product table itself:
 * which file is loaded, which two working files are currently being written
 * to, which location is currently active, and how many products were
 * approved so far. Backed by SharedPreferences so it survives the app being
 * closed and reopened — including which working files to keep writing to,
 * so the user never has to re-pick them after restarting the app.
 */
class SessionPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("stock_scanner_prefs", Context.MODE_PRIVATE)

    /** Display name of the originally-picked source Excel file (read once, never written back to). */
    var fileName: String?
        get() = prefs.getString(KEY_FILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_FILE_NAME, value).apply()

    /** SAF Uri (as a string) of the originally-picked source Excel file. */
    var fileUri: String?
        get() = prefs.getString(KEY_FILE_URI, null)
        set(value) = prefs.edit().putString(KEY_FILE_URI, value).apply()

    /**
     * File name (inside the app's private storage) of the working copy
     * holding locations + quantities, derived from [fileName]. Null until
     * it has actually been created.
     */
    var locationsQuantitiesFileName: String?
        get() = prefs.getString(KEY_LOCATIONS_QUANTITIES_FILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_LOCATIONS_QUANTITIES_FILE_NAME, value).apply()

    /**
     * File name (inside the app's private storage) of the working copy
     * holding the multiple-barcodes-per-מקט mapping, derived from [fileName].
     * Null until it has actually been created.
     */
    var multipleBarcodesFileName: String?
        get() = prefs.getString(KEY_MULTIPLE_BARCODES_FILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_MULTIPLE_BARCODES_FILE_NAME, value).apply()

    var currentLocation: String?
        get() = prefs.getString(KEY_CURRENT_LOCATION, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_LOCATION, value).apply()

    var approvedCount: Int
        get() = prefs.getInt(KEY_APPROVED_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_APPROVED_COUNT, value).apply()

    /**
     * Called whenever a brand-new Excel file is loaded, replacing any
     * previous session. The two working files are cleared here too — a new
     * source file means new working files, created right after this call.
     */
    fun resetForNewFile(fileName: String, uri: String) {
        prefs.edit()
            .putString(KEY_FILE_NAME, fileName)
            .putString(KEY_FILE_URI, uri)
            .putString(KEY_LOCATIONS_QUANTITIES_FILE_NAME, null)
            .putString(KEY_MULTIPLE_BARCODES_FILE_NAME, null)
            .putString(KEY_CURRENT_LOCATION, null)
            .putInt(KEY_APPROVED_COUNT, 0)
            .apply()
    }

    companion object {
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_FILE_URI = "file_uri"
        private const val KEY_LOCATIONS_QUANTITIES_FILE_NAME = "locations_quantities_file_name"
        private const val KEY_MULTIPLE_BARCODES_FILE_NAME = "multiple_barcodes_file_name"
        private const val KEY_CURRENT_LOCATION = "current_location"
        private const val KEY_APPROVED_COUNT = "approved_count"
    }
}
