package com.example.lifelens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.lifelens.camera.takePhoto
import com.example.lifelens.nexa.ModelManager
import com.example.lifelens.nexa.NexaVlmClient
import com.example.lifelens.tool.Audience
import com.example.lifelens.tool.SpeechSpeed
import com.example.lifelens.tool.TtsManager
import com.example.lifelens.ui.HistoryScreen
import com.example.lifelens.ui.HomeScreen
import com.example.lifelens.ui.IntroScreen
import com.example.lifelens.ui.PhotoScreen
import com.example.lifelens.ui.SetupScreen
import com.example.lifelens.ui.theme.LifeLensTheme
import com.example.lifelens.util.HistoryRepository
import com.example.lifelens.util.buildPrompt
import com.example.lifelens.util.copyTestImagesToDownloads
import com.example.lifelens.util.copyUriToFile
import com.example.lifelens.util.defaultQuestion
import com.example.lifelens.util.prepareImageForVlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class Phase { INTRO, SETUP, HOME, PHOTO, HISTORY }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { copyTestImagesToDownloads(this) }.start()

        setContent {
            LifeLensTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                // ── Navigation ──────────────────────────────────────────────
                var phase by remember { mutableStateOf(Phase.INTRO) }
                var previousPhase by remember { mutableStateOf(Phase.HOME) }

                // ── Setup ────────────────────────────────────────────────────
                var headline by remember { mutableStateOf("Welcome to LifeLens") }
                var detail by remember { mutableStateOf("One-tap setup, then point and understand.") }
                var progress by remember { mutableStateOf<Int?>(null) }
                var setupError by remember { mutableStateOf<String?>(null) }
                var setupRunning by remember { mutableStateOf(false) }

                // ── Permissions ───────────────────────────────────────────────
                var cameraGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                    )
                }
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> cameraGranted = granted }

                var audioGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                    )
                }
                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> audioGranted = granted }

                // ── Device detection ─────────────────────────────────────────
                val isEmulator = remember {
                    Build.FINGERPRINT.contains("generic", true) ||
                            Build.FINGERPRINT.contains("emulator", true) ||
                            Build.MODEL.contains("google_sdk", true) ||
                            Build.MODEL.contains("Emulator", true) ||
                            Build.BRAND.contains("generic", true) ||
                            Build.DEVICE.contains("generic", true) ||
                            Build.PRODUCT.contains("sdk", true)
                }
                var pluginId by remember { mutableStateOf(if (isEmulator) "cpu_gpu" else "npu") }

                // ── Camera ───────────────────────────────────────────────────
                val imageCapture = remember { ImageCapture.Builder().build() }
                var cameraReady by remember { mutableStateOf(false) }

                // ── Model ────────────────────────────────────────────────────
                val modelManager = remember { ModelManager(context) }
                val spec = remember { modelManager.defaultSpec() }
                var modelReady by remember { mutableStateOf(false) }
                var activeClient by remember { mutableStateOf<NexaVlmClient?>(null) }

                // ── Inference ─────────────────────────────────────────────────
                var uploadedImagePath by remember { mutableStateOf<String?>(null) }
                var questionText by remember { mutableStateOf("") }
                var isProcessing by remember { mutableStateOf(false) }
                var streamingAnswer by remember { mutableStateOf("") }
                var currentAnswer by remember { mutableStateOf("") }
                var inferJob by remember { mutableStateOf<Job?>(null) }

                // ── History ───────────────────────────────────────────────────
                val historyRepository = remember { HistoryRepository(context) }
                val historyEntries = remember {
                    mutableStateListOf<com.example.lifelens.util.HistoryEntry>()
                        .also { it.addAll(historyRepository.loadAll()) }
                }

                // ── TTS ───────────────────────────────────────────────────────
                var isSpeaking by remember { mutableStateOf(false) }
                var speechSpeed by remember { mutableStateOf(SpeechSpeed.SLOW) }
                val ttsManager = remember { TtsManager(context) { speaking -> isSpeaking = speaking } }

                // ── Voice recognition ─────────────────────────────────────────
                var isListening by remember { mutableStateOf(false) }
                var speechResultReady by remember { mutableStateOf(false) }
                val speechRecognizer = remember {
                    if (SpeechRecognizer.isRecognitionAvailable(context))
                        SpeechRecognizer.createSpeechRecognizer(context)
                    else null
                }

                val audience = Audience.ELDERLY

                // ── Lifecycle cleanup ─────────────────────────────────────────
                DisposableEffect(Unit) {
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partials: Bundle?) {
                            val text = partials?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )?.firstOrNull()
                            if (!text.isNullOrBlank()) questionText = text
                        }
                        override fun onResults(results: Bundle?) {
                            val text = results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )?.firstOrNull()
                            if (!text.isNullOrBlank()) questionText = text
                            isListening = false
                            speechResultReady = true   // triggers LaunchedEffect auto-submit
                        }
                        override fun onError(error: Int) { isListening = false }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    onDispose {
                        scope.launch { runCatching { activeClient?.destroy() } }
                        ttsManager.shutdown()
                        speechRecognizer?.destroy()
                    }
                }

                // ── Functions ─────────────────────────────────────────────────

                fun bindCamera() {
                    if (!cameraGranted) return
                    scope.launch {
                        runCatching {
                            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                this@MainActivity,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                imageCapture
                            )
                        }.onSuccess { cameraReady = true }
                         .onFailure { cameraReady = false }
                    }
                }

                suspend fun createAndInitClient(pid: String): Result<NexaVlmClient> =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val effectivePlugin = "npu"
                            val manifest = modelManager.getNexaManifest(spec)
                            val modelName = manifest?.ModelName?.takeIf { it.isNotBlank() } ?: spec.id
                            val modelDir = modelManager.modelDir(spec)
                            val entryPath = modelManager.entryPath(spec)
                            val dir = File(modelDir.absolutePath)
                            val entry = File(entryPath)
                            require(dir.exists() && dir.isDirectory) {
                                "Model dir missing: ${dir.absolutePath}"
                            }
                            require(entry.exists() && entry.isFile && entry.length() > 0L) {
                                "Model file missing/empty: ${entry.absolutePath}"
                            }
                            val mmproj = modelManager.mmprojPath(spec)
                            if (mmproj != null) {
                                val f = File(mmproj)
                                require(f.exists() && f.length() > 0L) {
                                    "mmproj missing/empty: $mmproj"
                                }
                            }
                            Log.i("LifeLens", "Init client: plugin=$effectivePlugin model=$modelName")
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

                fun handleAskWithImage() {
                    val q = questionText.trim()
                    if (q.isBlank() || isProcessing) return

                    val prev = inferJob
                    inferJob = scope.launch {
                        // Must signal native to stop BEFORE cancelling the Kotlin coroutine.
                        // Reversed order causes native thread to keep running after cancel,
                        // leaving the wrapper in dirty state → applyChatTemplate fails on next call.
                        runCatching { activeClient?.stopStream() }
                        prev?.cancelAndJoin()
                        isProcessing = true
                        streamingAnswer = ""
                        currentAnswer = ""

                        try {
                            val client = activeClient ?: error("Model not initialized.")

                            val imagePath: String = uploadedImagePath
                                ?: error("No image. Please take or upload a photo first.")

                            val prompt = buildPrompt(audience, q)
                            Log.d("LifeLens", "Ask: image=$imagePath q=$q")

                            client.generateWithImageStream(imagePath, prompt).collect { token ->
                                streamingAnswer += token
                            }

                            val finalAnswer = streamingAnswer
                            currentAnswer = finalAnswer

                            // Auto-read aloud
                            ttsManager.speak(finalAnswer)

                            // Save to persistent history
                            historyRepository.save(imagePath, q, finalAnswer)
                            historyEntries.clear()
                            historyEntries.addAll(historyRepository.loadAll())

                        } catch (t: Throwable) {
                            if (t is CancellationException) return@launch
                            Log.e("LifeLens", "Ask failed", t)
                            currentAnswer = "Sorry, something went wrong: ${t.message ?: "Unknown error"}"
                        } finally {
                            isProcessing = false
                            streamingAnswer = ""
                        }
                    }
                }

                fun startListening() {
                    if (!audioGranted) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return
                    }
                    isListening = true
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    }
                    speechRecognizer?.startListening(intent)
                }

                fun stopListening() {
                    speechRecognizer?.stopListening()
                    isListening = false
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

                            var lastError: Throwable? = null
                            var okClient: NexaVlmClient? = null
                            for (pid in listOf("npu")) {
                                val r = createAndInitClient(pid)
                                if (r.isSuccess) {
                                    okClient = r.getOrThrow()
                                    pluginId = pid
                                    break
                                } else {
                                    lastError = r.exceptionOrNull()
                                    Log.e("LifeLens", "Init failed plugin=$pid", lastError)
                                }
                            }
                            if (okClient == null) throw (lastError ?: RuntimeException("Init failed"))
                            runCatching { activeClient?.destroy() }
                            activeClient = okClient

                            if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            bindCamera()

                            phase = Phase.HOME

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

                // ── Bind camera as soon as HOME is reached ────────────────────
                LaunchedEffect(phase) {
                    if (phase == Phase.HOME && cameraGranted && !cameraReady) bindCamera()
                }

                // ── Auto-submit after voice recognition ───────────────────────
                LaunchedEffect(speechResultReady) {
                    if (speechResultReady) {
                        speechResultReady = false
                        if (questionText.isNotBlank()) handleAskWithImage()
                    }
                }

                // ── Upload launcher ───────────────────────────────────────────
                val uploadLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        // New image = new session: stop any in-flight inference first.
                        runCatching { activeClient?.stopStream() }
                        val staleJob = inferJob
                        inferJob = null
                        speechResultReady = false
                        if (isListening) { speechRecognizer?.stopListening(); isListening = false }
                        ttsManager.stop()
                        staleJob?.cancelAndJoin()
                        isProcessing = false
                        streamingAnswer = ""
                        currentAnswer = ""

                        runCatching {
                            val rawFile = File(context.cacheDir, "upload_raw_${System.currentTimeMillis()}.jpg")
                            copyUriToFile(context.contentResolver, uri, rawFile)
                            require(rawFile.exists() && rawFile.length() > 0L) { "Uploaded file is empty" }
                            val prepared = prepareImageForVlm(
                                context, rawFile.absolutePath, maxSize = 448, squareCrop = true
                            )
                            uploadedImagePath = prepared
                            questionText = defaultQuestion(audience)
                            phase = Phase.PHOTO
                        }.onFailure { Log.e("LifeLens", "Upload failed", it) }
                    }
                }

                // ── Bitmap for PhotoScreen ────────────────────────────────────
                val photoBitmap = remember(uploadedImagePath) {
                    uploadedImagePath?.let { path ->
                        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    }
                }

                // ── UI ────────────────────────────────────────────────────────
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

                        Phase.HOME -> HomeScreen(
                            onHistory = { previousPhase = phase; phase = Phase.HISTORY },
                            onCamera = {
                                scope.launch {
                                    // New image = new session: stop any in-flight inference first.
                                    // Without this, the old coroutine finishes and overwrites
                                    // currentAnswer with the previous image's answer.
                                    runCatching { activeClient?.stopStream() }
                                    val staleJob = inferJob
                                    inferJob = null
                                    speechResultReady = false
                                    if (isListening) { speechRecognizer?.stopListening(); isListening = false }
                                    ttsManager.stop()
                                    staleJob?.cancelAndJoin()
                                    isProcessing = false
                                    streamingAnswer = ""
                                    currentAnswer = ""

                                    runCatching {
                                        if (!cameraGranted) {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            return@runCatching
                                        }
                                        if (!cameraReady) bindCamera()
                                        val raw = File(
                                            context.cacheDir,
                                            "capture_${System.currentTimeMillis()}.jpg"
                                        )
                                        val captured = takePhoto(context, imageCapture, raw)
                                        require(captured.exists() && captured.length() > 0L) {
                                            "Captured image is empty"
                                        }
                                        val prepared = prepareImageForVlm(
                                            context, captured.absolutePath, maxSize = 448, squareCrop = true
                                        )
                                        uploadedImagePath = prepared
                                        questionText = defaultQuestion(audience)
                                        phase = Phase.PHOTO
                                    }.onFailure { Log.e("LifeLens", "Capture failed", it) }
                                }
                            },
                            onUpload = { uploadLauncher.launch("image/*") },
                            cameraGranted = cameraGranted,
                            cameraReady = cameraReady
                        )

                        Phase.PHOTO -> PhotoScreen(
                            bitmap = photoBitmap,
                            currentAnswer = currentAnswer,
                            streamingAnswer = streamingAnswer,
                            isProcessing = isProcessing,
                            questionText = questionText,
                            onQuestionTextChange = { questionText = it },
                            isListening = isListening,
                            onMicDown = { startListening() },
                            onMicUp = { stopListening() },
                            onSubmit = { handleAskWithImage() },
                            onHome = {
                                scope.launch {
                                    runCatching { activeClient?.stopStream() }
                                    val staleJob = inferJob
                                    inferJob = null
                                    speechResultReady = false
                                    if (isListening) { speechRecognizer?.stopListening(); isListening = false }
                                    ttsManager.stop()
                                    staleJob?.cancelAndJoin()
                                    isProcessing = false
                                    streamingAnswer = ""
                                    currentAnswer = ""
                                    uploadedImagePath = null
                                    phase = Phase.HOME
                                }
                            },
                            onHistory = { previousPhase = phase; phase = Phase.HISTORY },
                            isSpeaking = isSpeaking,
                            onSpeakClick = { text -> ttsManager.speak(text) },
                            onStopSpeaking = { ttsManager.stop() },
                            speechSpeed = speechSpeed,
                            onSpeedChange = { speed ->
                                speechSpeed = speed
                                ttsManager.setSpeechRate(speed.rate)
                            }
                        )

                        Phase.HISTORY -> HistoryScreen(
                            entries = historyEntries,
                            onBack = { phase = previousPhase }
                        )
                    }
                }
            }
        }
    }
}
