package com.example.lifelens.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "TestImages"

private val TEST_ASSETS = listOf(
    "test_rx_bottle.jpg"        to "LifeLens - Prescription Bottle",
    "test_pills_hand.jpg"       to "LifeLens - Pills Bottle (hand)",
    "test_pills2_hand.jpg"      to "LifeLens - Supplement Bottle (hand)",
    "test_kitchen_knife.jpg"    to "LifeLens - Kitchen Knife",
    "test_cat.jpg"              to "LifeLens - Cat",
    "test_rotten_apple.jpg"     to "LifeLens - Rotten Apple",
    "test_expired_bread.jpg"    to "LifeLens - Expired Bread"
)

private const val PREFS_KEY = "test_images_copied_v2"

/**
 * Copies bundled test images to the device's Downloads folder on first launch.
 * Uses MediaStore on API 29+ for scoped storage compliance.
 * Safe to call repeatedly — skips if already done.
 */
fun copyTestImagesToDownloads(context: Context) {
    val prefs = context.getSharedPreferences("lifelens_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean(PREFS_KEY, false)) return

    var copied = 0
    for ((assetName, displayName) in TEST_ASSETS) {
        runCatching {
            val bytes = context.assets.open(assetName).use { it.readBytes() }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$displayName.jpg")
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, "$displayName.jpg").writeBytes(bytes)
            }
            copied++
            Log.i(TAG, "Copied $assetName -> Downloads")
        }.onFailure {
            Log.w(TAG, "Failed to copy $assetName: ${it.message}")
        }
    }

    if (copied > 0) {
        prefs.edit().putBoolean(PREFS_KEY, true).apply()
        Log.i(TAG, "Copied $copied test images to Downloads")
    }
}
