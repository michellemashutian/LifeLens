package com.example.lifelens.nexa

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlin.math.max

private val manifestJson = Json { ignoreUnknownKeys = true }

data class ModelSpec(
    val id: String,
    val displayName: String,
    val baseResolveUrl: String, // MUST be .../resolve/main
    val files: List<String>,
    val entryFile: String,
    val mmprojFile: String? = null
)

data class DownloadProgress(
    val modelId: String,
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val overallPercent: Int
)

class ModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "OmniNeural-4B-mobile",
            displayName = "OmniNeural-4B-mobile (≈4.9GB)",
            baseResolveUrl = "https://huggingface.co/NexaAI/OmniNeural-4B-mobile/resolve/main",
            files = listOf(
                "nexa.manifest",
                "files-1-1.nexa",
                "attachments-1-3.nexa",
                "attachments-2-3.nexa",
                "attachments-3-3.nexa",
                "weights-1-8.nexa",
                "weights-2-8.nexa",
                "weights-3-8.nexa",
                "weights-4-8.nexa",
                "weights-5-8.nexa",
                "weights-6-8.nexa",
                "weights-7-8.nexa",
                "weights-8-8.nexa"
            ),
            entryFile = "files-1-1.nexa",
            mmprojFile = "attachments-1-3.nexa"
        )
    )

    fun defaultSpec(): ModelSpec = models.first()
    fun modelDir(spec: ModelSpec): File = File(context.filesDir, "models/${spec.id}")

    fun getNexaManifest(spec: ModelSpec): NexaManifestBean? {
        return runCatching {
            val manifestFile = File(modelDir(spec), "nexa.manifest")
            if (!manifestFile.exists() || !manifestFile.isFile) return@runCatching null
            val str = manifestFile.bufferedReader().use { it.readText() }
            manifestJson.decodeFromString<NexaManifestBean>(str)
        }.getOrNull()
    }
    fun entryPath(spec: ModelSpec): String = File(modelDir(spec), spec.entryFile).absolutePath
    fun mmprojPath(spec: ModelSpec): String? = spec.mmprojFile?.let { File(modelDir(spec), it).absolutePath }
    fun defaultEntryPath(): String = entryPath(defaultSpec())

    fun missingFiles(spec: ModelSpec): List<String> {
        val dir = modelDir(spec)
        if (!dir.exists()) return spec.files
        return spec.files.filter { name ->
            val f = File(dir, name)
            !f.exists() || f.length() <= 0L || isBadDownloadedFile(f)
        }
    }

    fun isModelReady(spec: ModelSpec): Boolean = missingFiles(spec).isEmpty()

    private fun buildUrl(spec: ModelSpec, fileName: String): String {
        return "${spec.baseResolveUrl}/$fileName?download=true"
    }

    private fun headSize(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .head()
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun isBadDownloadedFile(f: File): Boolean {
        if (!f.exists() || f.length() <= 0L) return true

        if (f.name.endsWith(".nexa") && f.length() < 1024 * 1024) {
            val headBytes = runCatching {
                f.inputStream().use { it.readNBytesCompat(4096) }
            }.getOrNull() ?: return true

            val s = headBytes.toString(Charsets.UTF_8).lowercase()

            if (s.contains("<html")) return true
            if (s.contains("access denied")) return true
            if (s.contains("forbidden")) return true
            if (s.contains("too many requests")) return true
            if (s.contains("rate limit")) return true
            if (s.contains("not found")) return true
            if (s.contains("expired")) return true
            if (s.contains("error")) return true
            if (s.contains("git-lfs.github.com/spec/v1")) return true
        }

        return false
    }

    /**
     * Paths to check for pre-deployed model files (via adb push).
     *
     * Primary (recommended — no permissions needed):
     *   adb shell mkdir -p /sdcard/Android/data/com.example.lifelens/files/models/OmniNeural-4B-mobile
     *   adb push *.nexa /sdcard/Android/data/com.example.lifelens/files/models/OmniNeural-4B-mobile/
     */
    private fun externalModelDirs(spec: ModelSpec): List<File> = listOfNotNull(
        // Primary: app's own external files dir (no permissions needed)
        context.getExternalFilesDir(null)?.let { File(it, "models/${spec.id}") },
        // Fallback: /sdcard/LifeLens/models/<id>/
        File(Environment.getExternalStorageDirectory(), "LifeLens/models/${spec.id}")
    )

    /**
     * Check if model files were pre-deployed to external storage (via adb push).
     * Returns the directory containing all model files, or null if not found.
     */
    private fun findExternalModel(spec: ModelSpec): File? {
        for (dir in externalModelDirs(spec)) {
            if (!dir.exists()) continue
            val allPresent = spec.files.all { name ->
                val f = File(dir, name)
                f.exists() && f.length() > 0L && !isBadDownloadedFile(f)
            }
            if (allPresent) {
                Log.i(TAG, "Found pre-deployed model at: ${dir.absolutePath}")
                return dir
            }
        }
        return null
    }

    /**
     * Copy model files from external storage (adb push location) to internal storage.
     */
    fun copyExternalModel(spec: ModelSpec, sourceDir: File): Flow<DownloadProgress> = flow {
        val dir = modelDir(spec)
        if (!dir.exists()) dir.mkdirs()

        val fileCount = spec.files.size
        emit(DownloadProgress(spec.id, "(copying from device)", 0, fileCount, 0L, 100L, 0))

        spec.files.forEachIndexed { idx, fileName ->
            val outFile = File(dir, fileName)
            val srcFile = File(sourceDir, fileName)

            if (outFile.exists() && outFile.length() > 0L && !isBadDownloadedFile(outFile)) {
                emit(DownloadProgress(spec.id, fileName, idx + 1, fileCount, 0L, 100L,
                    ((idx + 1) * 100 / fileCount).coerceIn(0, 100)))
                return@forEachIndexed
            }

            srcFile.inputStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(1024 * 256)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                }
            }

            emit(DownloadProgress(spec.id, fileName, idx + 1, fileCount, 0L, 100L,
                ((idx + 1) * 100 / fileCount).coerceIn(0, 100)))
        }

        val entry = File(dir, spec.entryFile)
        require(entry.exists() && entry.length() > 0L) {
            "Entry file missing after copy: ${entry.absolutePath}"
        }

        emit(DownloadProgress(spec.id, "(done)", fileCount, fileCount, 100L, 100L, 100))
    }.flowOn(Dispatchers.IO)

    private fun hasBundledAssets(spec: ModelSpec): Boolean {
        return try {
            val assetPath = "models/${spec.id}"
            val listed = context.assets.list(assetPath) ?: emptyArray()
            spec.files.all { it in listed }
        } catch (_: Throwable) {
            false
        }
    }

    fun copyBundledModel(spec: ModelSpec): Flow<DownloadProgress> = flow {
        val dir = modelDir(spec)
        if (!dir.exists()) dir.mkdirs()

        val fileCount = spec.files.size

        emit(DownloadProgress(spec.id, "(preparing)", 0, fileCount, 0L, 100L, 0))

        spec.files.forEachIndexed { idx, fileName ->
            val outFile = File(dir, fileName)

            if (outFile.exists() && outFile.length() > 0L && !isBadDownloadedFile(outFile)) {
                emit(DownloadProgress(spec.id, fileName, idx + 1, fileCount, 0L, 100L,
                    ((idx + 1) * 100 / fileCount).coerceIn(0, 100)))
                return@forEachIndexed
            }

            val assetPath = "models/${spec.id}/$fileName"
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(1024 * 256)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                }
            }

            emit(DownloadProgress(spec.id, fileName, idx + 1, fileCount, 0L, 100L,
                ((idx + 1) * 100 / fileCount).coerceIn(0, 100)))
        }

        val entry = File(dir, spec.entryFile)
        require(entry.exists() && entry.length() > 0L) {
            "Entry file missing after asset copy: ${entry.absolutePath}"
        }

        emit(DownloadProgress(spec.id, "(done)", fileCount, fileCount, 100L, 100L, 100))
    }.flowOn(Dispatchers.IO)

    fun downloadModel(spec: ModelSpec): Flow<DownloadProgress> = flow {
        // 1) Check if model already exists in internal storage
        if (isModelReady(spec)) {
            Log.i(TAG, "Model already ready in internal storage")
            emit(DownloadProgress(spec.id, "(done)", spec.files.size, spec.files.size, 100L, 100L, 100))
            return@flow
        }

        // 2) Check for pre-deployed model on external storage (via adb push)
        val externalDir = findExternalModel(spec)
        if (externalDir != null) {
            Log.i(TAG, "Copying pre-deployed model from: ${externalDir.absolutePath}")
            copyExternalModel(spec, externalDir).collect { emit(it) }
            return@flow
        }

        // 3) Use bundled assets if available (no internet needed)
        if (hasBundledAssets(spec)) {
            copyBundledModel(spec).collect { emit(it) }
            return@flow
        }

        // 4) Fall back to HTTP download from Hugging Face

        val dir = modelDir(spec)
        if (!dir.exists()) dir.mkdirs()

        val sizes = spec.files.map { f -> headSize(buildUrl(spec, f)) }
        val totalAll = max(sizes.sum(), 1L)

        var downloadedAll = spec.files.sumOf { name ->
            val f = File(dir, name)
            if (f.exists() && f.length() > 0L && !isBadDownloadedFile(f)) f.length() else 0L
        }

        emit(
            DownloadProgress(
                modelId = spec.id,
                fileName = "(prepare)",
                fileIndex = 0,
                fileCount = spec.files.size,
                bytesDownloaded = downloadedAll,
                bytesTotal = totalAll,
                overallPercent = ((downloadedAll * 100) / totalAll).toInt().coerceIn(0, 100)
            )
        )

        spec.files.forEachIndexed { idx, fileName ->
            val outFile = File(dir, fileName)
            val partFile = File(dir, "$fileName.part")

            if (outFile.exists() && isBadDownloadedFile(outFile)) {
                outFile.delete()
            }

            if (outFile.exists() && outFile.length() > 0L) {
                emit(
                    DownloadProgress(
                        modelId = spec.id,
                        fileName = fileName,
                        fileIndex = idx + 1,
                        fileCount = spec.files.size,
                        bytesDownloaded = downloadedAll,
                        bytesTotal = totalAll,
                        overallPercent = ((downloadedAll * 100) / totalAll).toInt().coerceIn(0, 100)
                    )
                )
                return@forEachIndexed
            }

            var resumeFrom = if (partFile.exists()) partFile.length() else 0L
            val maxRetry = 4
            var attempt = 0
            var doneThisFile = false

            while (!doneThisFile) {
                attempt++

                val url = buildUrl(spec, fileName)
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")

                if (resumeFrom > 0L) reqBuilder.header("Range", "bytes=$resumeFrom-")
                val req = reqBuilder.build()

                try {
                    client.newCall(req).execute().use { resp ->
                        validateResponseOrThrow(resp, fileName, url)

                        val body = resp.body ?: throw IllegalStateException("Empty body: $fileName")

                        val ctype = (resp.header("Content-Type") ?: "").lowercase()
                        if (ctype.contains("text/html")) {
                            throw IllegalStateException("Got HTML for $fileName (code=${resp.code}, contentType=$ctype)")
                        }

                        FileOutputStream(partFile, resumeFrom > 0L).use { fos ->
                            val buf = ByteArray(1024 * 256)
                            val input = body.byteStream()

                            var fileDownloadedThisAttempt = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                fos.write(buf, 0, read)
                                fileDownloadedThisAttempt += read

                                val overall = (((downloadedAll + resumeFrom + fileDownloadedThisAttempt) * 100) / totalAll)
                                    .toInt()
                                    .coerceIn(0, 100)

                                emit(
                                    DownloadProgress(
                                        modelId = spec.id,
                                        fileName = fileName,
                                        fileIndex = idx + 1,
                                        fileCount = spec.files.size,
                                        bytesDownloaded = downloadedAll + resumeFrom + fileDownloadedThisAttempt,
                                        bytesTotal = totalAll,
                                        overallPercent = overall
                                    )
                                )
                            }
                        }
                    }

                    if (outFile.exists()) outFile.delete()
                    if (!partFile.renameTo(outFile)) {
                        throw IllegalStateException("Failed to rename .part for $fileName")
                    }

                    if (isBadDownloadedFile(outFile)) {
                        outFile.delete()
                        throw IllegalStateException("Downloaded file looks invalid: $fileName")
                    }

                    downloadedAll += outFile.length()
                    resumeFrom = 0L
                    doneThisFile = true
                } catch (t: Throwable) {
                    resumeFrom = if (partFile.exists()) partFile.length() else 0L
                    if (attempt >= maxRetry) {
                        throw IllegalStateException(
                            "Download failed after $maxRetry attempts: $fileName. ${t.message}",
                            t
                        )
                    }
                }
            }
        }

        // ✅ 最终校验：entry 必须存在
        val entry = File(dir, spec.entryFile)
        require(entry.exists() && entry.length() > 0L) {
            "Entry file missing after download: ${entry.absolutePath}"
        }

        val missing = missingFiles(spec)
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Missing/invalid files after download: ${missing.joinToString()}")
        }

        emit(
            DownloadProgress(
                modelId = spec.id,
                fileName = "(done)",
                fileIndex = spec.files.size,
                fileCount = spec.files.size,
                bytesDownloaded = totalAll,
                bytesTotal = totalAll,
                overallPercent = 100
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun validateResponseOrThrow(resp: Response, fileName: String, url: String) {
        val code = resp.code
        val ok = resp.isSuccessful && (code == 200 || code == 206)
        if (!ok) {
            val ctype = resp.header("Content-Type") ?: ""
            val loc = resp.header("Location") ?: ""
            throw IllegalStateException(
                "HTTP $code for $fileName (contentType=$ctype, location=$loc, url=$url)"
            )
        }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = this.read(buffer, offset, n - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == n) buffer else buffer.copyOf(offset)
    }

    companion object {
        private const val TAG = "ModelManager"
    }
}
