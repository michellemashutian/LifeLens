package com.example.lifelens.util

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

suspend fun copyUriToFile(resolver: ContentResolver, uri: Uri, outFile: File) {
    withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(1024 * 256)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                }
            }
        } ?: throw IllegalStateException("Cannot open input stream for uri=$uri")
    }
}

suspend fun copyAssetToCache(context: android.content.Context, assetName: String): String =
    withContext(Dispatchers.IO) {
        val outFile = File(context.cacheDir, "asset_${assetName}_${System.currentTimeMillis()}.jpg")
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(1024 * 256)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                }
            }
        }
        require(outFile.exists() && outFile.length() > 0L) { "Asset copy failed: $assetName" }
        outFile.absolutePath
    }
