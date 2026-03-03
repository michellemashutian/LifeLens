package com.example.lifelens.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

private const val TAG = "HistoryRepository"
private const val PREFS_NAME = "lifelens_history"
private const val KEY_ENTRIES = "entries"
private const val MAX_ENTRIES = 50

data class HistoryEntry(
    val id: String,
    val imagePath: String,   // absolute path in filesDir/history/ — survives cache clears
    val question: String,
    val answer: String,
    val timestamp: Long
)

class HistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val historyDir = File(context.filesDir, "history").also { it.mkdirs() }
    private val gson = Gson()

    /** Save a new Q&A entry. Copies the image to permanent storage. */
    fun save(imagePath: String, question: String, answer: String) {
        val id = UUID.randomUUID().toString()
        val savedImagePath = runCatching {
            val dest = File(historyDir, "$id.jpg")
            File(imagePath).copyTo(dest, overwrite = true)
            dest.absolutePath
        }.onFailure {
            Log.w(TAG, "Failed to copy history image: ${it.message}")
        }.getOrElse { imagePath }

        val entries = loadAll().toMutableList()
        entries.add(0, HistoryEntry(id, savedImagePath, question, answer, System.currentTimeMillis()))

        // Prune oldest entries beyond max
        if (entries.size > MAX_ENTRIES) {
            val pruned = entries.drop(MAX_ENTRIES)
            pruned.forEach { runCatching { File(it.imagePath).delete() } }
        }
        val kept = entries.take(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(kept)).apply()
        Log.i(TAG, "Saved history entry $id")
    }

    /** Returns all entries, newest first. */
    fun loadAll(): List<HistoryEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson<List<HistoryEntry>>(json, type) ?: emptyList()
        }.onFailure {
            Log.w(TAG, "Failed to parse history: ${it.message}")
        }.getOrElse { emptyList() }
    }
}
