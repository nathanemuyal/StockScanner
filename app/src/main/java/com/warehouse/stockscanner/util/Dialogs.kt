package com.warehouse.stockscanner.util

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** A simple "אישור"-only error dialog — shared so a failed save is never reported as if it had succeeded. */
fun AppCompatActivity.showErrorDialog(title: String, message: String) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("אישור", null)
        .show()
}
