package com.example.lifelens

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.lifelens.camera.takePhoto
import com.example.lifelens.nexa.ModelManager
import com.example.lifelens.nexa.NexaVlmClient
import com.example.lifelens.tool.Audience
import com.example.lifelens.ui.IntroScreen
import com.example.lifelens.ui.ReadyScreen
import com.example.lifelens.ui.SetupScreen
import com.example.lifelens.ui.theme.LifeLensTheme
import com.example.lifelens.util.buildPrompt
import com.example.lifelens.util.copyAssetToCache
import com.example.lifelens.util.copyUriToFile
import com.example.lifelens.util.defaultQuestion
import com.example.lifelens.util.prepareImageForVlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

enum class Phase { INTRO, SETUP, READY }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LifeLensTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var phase by remember { mutableStateOf(Phase.INTRO) }

                // status
                var headline by remember { mutableStateOf("Welcome to LifeLens") }
                var detail by remember { mutableStateOf("One-tap setup, then point and understand.") }
                var progress by remember { mutableStateOf<Int?>(null) }

                // setup error
                var setupError by remember { mutableStateOf<String?>(null) }
                var setupRunning by remember { mutableStateOf(false) }

                // permissions
                var cameraGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                    )
                }
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted -> cameraGranted = granted }

                // emulator detect
                val isEmulator = remember {
                    Build.FINGERPRINT.contains("generic", true) ||
                            Build.FINGERPRINT.contains("emulator", true) ||
                            Build.MODEL.contains("google_sdk", true) ||
                            Build.MODEL.contains("Emulator", true) ||
                            Build.BRAND.contains("generic", true) ||
                            Build.DEVICE.contains("generic", true) ||
                            Build.PRODUCT.contains("sdk", true)
                }

                // default plugin: emulator -> cpu_gpu, device -> npu
                var pluginId by remember { mutableStateOf(if (isEmulator) "cpu_gpu" else "npu") }

                // camera
                val previewView = remember { PreviewView(context) }
                val imageCapture = remember { ImageCapture.Builder().build() }
                var cameraReady by remember { mutableStateOf(false) }

                // model
                val modelManager = remember { ModelManager(context) }
                val spec = remember { modelManager.defaultSpec() }
                var modelReady by remember { mutableStateOf(false) }

                // nexa
                var activeClient by remember { mutableStateOf<NexaVlmClient?>(null) }

                // Ask-with-image states
                var uploadedImagePath by remember { mutableStateOf<String?>(null) }
                var questionText by remember { mutableStateOf("") }
                var isProcessing by remember { mutableStateOf(false) }
                var streamingAnswer by remember { mutableStateOf("") }
                var inferJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }


                // audience
                var audience by remember { mutableStateOf(Audience.ELDERLY) }

                DisposableEffect(Unit) {
                    onDispose { scope.launch { runCatching { activeClient?.destroy() } } }
                }

                // Upload photo -> copy to cache -> prepare for VLM -> store absolute path
                val uploadLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        runCatching {
                            headline = "Loading image..."
                            detail = "Copying uploaded photo."
                            progress = null

                            val rawFile = File(context.cacheDir, "upload_raw_${System.currentTimeMillis()}.jpg")
                            copyUriToFile(context.contentResolver, uri, rawFile)
                            require(rawFile.exists() && rawFile.length() > 0L) { "Uploaded file is empty" }

                            val prepared = prepareImageForVlm(context, rawFile.absolutePath, maxSize = 768, squareCrop = false)
                            uploadedImagePath = prepared

                            if (questionText.isBlank()) questionText = defaultQuestion(audience)

                            headline = "Image ready"
                            detail = "Tap Ask, or Quick Test."
                            Log.d("LifeLens", "Uploaded prepared image: $prepared (${File(prepared).length()} bytes)")
                        }.onFailure {
                            headline = "Upload failed"
                            detail = it.message ?: "Unknown error"
                        }
                    }
                }

                fun bindCamera() {
                    if (!cameraGranted) return
                    scope.launch {
                        runCatching {
                            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                this@MainActivity,
                                androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }.onSuccess {
                            cameraReady = true
                        }.onFailure {
                            cameraReady = false
                            headline = "Camera not ready"
                            detail = "If emulator preview is black: Emulator → Settings → Camera → Webcam0."
                        }
                    }
                }

                suspend fun createAndInitClient(pid: String): Result<NexaVlmClient> =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val requested = pid.trim().lowercase()
                            require(requested == "npu" || requested == "cpu_gpu") {
                                "Invalid pluginId=$pid. Use \"npu\" or \"cpu_gpu\"."
                            }

                            // emulator 强制 cpu_gpu
                            // val effectivePlugin = if (isEmulator) "cpu_gpu" else requested
                            val effectivePlugin = "npu"

                            val manifest = modelManager.getNexaManifest(spec)
                            val modelName = manifest?.ModelName?.takeIf { it.isNotBlank() } ?: spec.id

                            val modelDir = modelManager.modelDir(spec)
                            val entryPath = modelManager.entryPath(spec)

                            val dir = File(modelDir.absolutePath)
                            val entry = File(entryPath)
                            require(dir.exists() && dir.isDirectory) { "Model dir missing: ${dir.absolutePath}" }
                            require(entry.exists() && entry.isFile && entry.length() > 0L) { "Model file missing/empty: ${entry.absolutePath}" }

                            Log.i("LifeLens", "createAndInitClient(): requested=$requested effective=$effectivePlugin")
                            Log.i("LifeLens", "createAndInitClient(): modelName=$modelName")
                            Log.i("LifeLens", "createAndInitClient(): modelDir=${dir.absolutePath}")
                            Log.i("LifeLens", "createAndInitClient(): modelFile=${entry.absolutePath} len=${entry.length()}")

                            val mmproj = modelManager.mmprojPath(spec)
                            Log.i("LifeLens", "createAndInitClient(): mmproj=$mmproj exists=${mmproj?.let { File(it).exists() }} len=${mmproj?.let { File(it).length() }}")

                            if (mmproj != null) {
                                val f = File(mmproj)
                                require(f.exists() && f.length() > 0L) { "mmproj missing/empty: $mmproj" }
                            }

                            val client = NexaVlmClient(
                                context = context,
                                modelName = modelName,
                                pluginId = effectivePlugin,
                                modelFilePath = entry.absolutePath,
                                modelDirPath = dir.absolutePath,
                                mmprojPath = mmproj,
                                npuLibFolderPath = applicationInfo.nativeLibraryDir
                            )

                            client.init().getOrThrow()
                            client
                        }
                    }

                suspend fun stopPreviousInference() {
                    // 不要 cancel inferJob（否则会自杀）
                    runCatching { activeClient?.stopStream() } // 停 native 推理
                    isProcessing = false
                }


                fun handleAskWithImage() {
                    val q = questionText.trim()
                    if (q.isBlank() || isProcessing) return

                    val prev = inferJob
                    inferJob = scope.launch {
                        prev?.cancelAndJoin()
                        runCatching { activeClient?.stopStream() }

                        // 再开始这一轮
                        isProcessing = true
                        streamingAnswer = ""

                        try {
                            val client = activeClient ?: error("Model not initialized. Tap Get Started first.")

                            val imagePath: String = uploadedImagePath ?: run {
                                if (!(cameraGranted && cameraReady)) {
                                    error("No image yet. Please Upload Photo, or use Quick Test.")
                                }
                                headline = "Capturing..."
                                detail = q

                                val raw = File(context.cacheDir, "ask_capture_raw_${System.currentTimeMillis()}.jpg")
                                val captured = takePhoto(context, imageCapture, raw)
                                require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }

                                val prepared = prepareImageForVlm(
                                    context,
                                    captured.absolutePath,
                                    maxSize = 768,
                                    squareCrop = false
                                )
                                uploadedImagePath = prepared
                                prepared
                            }

                            val prompt = buildPrompt(audience, q)

                            headline = "Thinking..."
                            detail = q
                            Log.d("LifeLens", "Ask with image=$imagePath plugin=$pluginId")

                            client.generateWithImageStream(imagePath, prompt).collect { token ->
                                streamingAnswer += token
                            }

                            headline = "Ready"
                            detail = q

                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) {
                                // 正常取消，不算错误
                                Log.d("LifeLens", "Inference cancelled")
                                return@launch
                            }

                            Log.e("LifeLens", "Ask failed", t)
                            headline = "Failed"
                            detail = t.message ?: "Unknown error"
                            if (streamingAnswer.isBlank()) {
                                streamingAnswer = "Error: ${t.message ?: "Unknown"}"
                            }
                        }
                        finally {
                            isProcessing = false
                        }
                    }
                }


                fun quickTestDefaultPhoto() {
                    if (isProcessing) return
                    val prev = inferJob
                    inferJob = scope.launch {
                        prev?.cancelAndJoin()
                        runCatching { activeClient?.stopStream() }

                        isProcessing = true
                        streamingAnswer = ""

                        try {
                            val client = activeClient ?: error("Model not initialized. Tap Get Started first.")

                            headline = "Loading default image..."
                            detail = "Preparing test..."

                            val raw = copyAssetToCache(context, "default_test.jpg")
                            val prepared = prepareImageForVlm(context, raw, maxSize = 768, squareCrop = false)
                            uploadedImagePath = prepared

                            if (questionText.isBlank()) questionText = defaultQuestion(audience)
                            val prompt = buildPrompt(Audience.ELDERLY, questionText)

                            headline = "Thinking..."
                            detail = questionText

                            client.generateWithImageStream(prepared, prompt).collect { token ->
                                streamingAnswer += token
                            }

                            headline = "Ready"
                            detail = questionText

                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) {
                                // 正常取消，不算错误
                                Log.d("LifeLens", "Inference cancelled")
                                return@launch
                            }

                            Log.e("LifeLens", "Ask failed", t)
                            headline = "Failed"
                            detail = t.message ?: "Unknown error"
                            if (streamingAnswer.isBlank()) {
                                streamingAnswer = "Error: ${t.message ?: "Unknown"}"
                            }
                        }
                        finally {
                            isProcessing = false
                        }
                    }
                }


                fun startSetup() {
                    if (setupRunning) return
                    phase = Phase.SETUP
                    setupError = null
                    setupRunning = true

                    scope.launch {
                        try {
                            headline = "Checking model..."
                            detail = "Looking for local model files."
                            progress = null

                            val missing = modelManager.missingFiles(spec)
                            modelReady = missing.isEmpty()

                            if (!modelReady) {
                                headline = "Downloading model..."
                                detail = "This may take a while (large file). Keep the app open."
                                progress = 0

                                modelManager.downloadModel(spec).collect { p ->
                                    progress = p.overallPercent.coerceIn(0, 100)
                                    detail = "Downloading… ${progress}%  (${p.fileIndex}/${p.fileCount})"
                                }

                                val missingAfter = modelManager.missingFiles(spec)
                                modelReady = missingAfter.isEmpty()
                                if (!modelReady) error("Download incomplete. Missing: ${missingAfter.joinToString()}")
                            }

                            headline = "Initializing..."
                            detail = "Trying NPU…"
                            progress = null

                            val tryOrder = listOf("npu")
                            var lastError: Throwable? = null
                            var okClient: NexaVlmClient? = null

                            for (pid in tryOrder) {
                                val r = createAndInitClient(pid)
                                if (r.isSuccess) {
                                    okClient = r.getOrThrow()
                                    pluginId = pid
                                    break
                                } else {
                                    lastError = r.exceptionOrNull()
                                    Log.e("LifeLens", "Init failed for plugin=$pid", lastError)
                                }
                            }

                            if (okClient == null) throw (lastError ?: RuntimeException("Init failed for all plugins"))

                            runCatching { activeClient?.destroy() }
                            activeClient = okClient

                            detail = if (pluginId == "npu") "Started on NPU." else "Started on CPU/GPU."

                            headline = "Almost ready"
                            detail = "We’ll ask for camera permission so you can capture."
                            progress = null

                            if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            bindCamera()

                            phase = Phase.READY
                            headline = "Ready"
                            detail = "Upload or Capture, ask a question, or run Quick Test."
                            progress = null

                        } catch (t: Throwable) {
                            Log.e("LifeLens", "Setup failed", t)
                            setupError = buildString {
                                append(t.message ?: "Unknown error")
                                val st = t.stackTraceToString().take(2500)
                                if (st.isNotBlank()) append("\n\n").append(st)
                            }
                            headline = "Setup failed"
                            detail = "See details below."
                            progress = null
                            phase = Phase.SETUP
                        } finally {
                            setupRunning = false
                        }
                    }
                }

                Surface {
                    when (phase) {
                        Phase.INTRO -> IntroScreen(
                            title = "LifeLens",
                            subtitle = "Understand what you see.\nA simple assistant for seniors.",
                            primaryText = if (setupRunning) "Starting…" else "Get Started",
                            onPrimary = { startSetup() }
                        )

                        Phase.SETUP -> SetupScreen(
                            headline = headline,
                            detail = detail,
                            progress = progress,
                            errorText = setupError,
                            running = setupRunning,
                            onRetry = { startSetup() },
                            onBack = {
                                if (!setupRunning) {
                                    phase = Phase.INTRO
                                    headline = "Welcome to LifeLens"
                                    detail = "One-tap setup, then point and understand."
                                    progress = null
                                    setupError = null
                                }
                            }
                        )

                        Phase.READY -> ReadyScreen(
                            previewView = previewView,
                            cameraGranted = cameraGranted,
                            cameraReady = cameraReady,
                            uploadedImagePath = uploadedImagePath,
                            onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            onBindCamera = { bindCamera() },
                            onUpload = { uploadLauncher.launch("image/*") },
                            onCapture = {
                                scope.launch {
                                    runCatching {
                                        if (!(cameraGranted && cameraReady)) error("Camera not ready")
                                        val raw = File(context.cacheDir, "capture_raw_${System.currentTimeMillis()}.jpg")
                                        val captured = takePhoto(context, imageCapture, raw)
                                        require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }

                                        val prepared = prepareImageForVlm(context, captured.absolutePath, maxSize = 768, squareCrop = false)
                                        uploadedImagePath = prepared

                                        if (questionText.isBlank()) questionText = defaultQuestion(audience)
                                        headline = "Image ready"
                                        detail = "Type a question and tap Ask."
                                    }.onFailure {
                                        headline = "Capture failed"
                                        detail = it.message ?: "Unknown error"
                                    }
                                }
                            },
                            headline = headline,
                            detail = detail,
                            questionText = questionText,
                            onQuestionTextChange = { questionText = it },
                            isProcessing = isProcessing,
                            streamingAnswer = streamingAnswer,
                            onAskSubmit = { handleAskWithImage() },
                            onQuickTest = { quickTestDefaultPhoto() }
                        )
                    }
                }
            }
        }
    }
}
