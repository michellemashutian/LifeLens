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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.example.lifelens.camera.takePhoto
import com.example.lifelens.nexa.ModelManager
import com.example.lifelens.nexa.NexaVlmClient
import com.example.lifelens.tool.Audience
import com.example.lifelens.ui.theme.LifeLensTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

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

                            // ✅ prepare for VLM
                            val prepared = prepareImageForVlm(context, rawFile.absolutePath, maxSize = 448, squareCrop = true)
                            uploadedImagePath = prepared

                            if (questionText.isBlank()) questionText = defaultQuestion(audience)

                            headline = "Image ready"
                            detail = "Tap Ask or Quick Test."
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

                            // ✅ emulator 强制 cpu_gpu
                            val effectivePlugin = if (isEmulator) "cpu_gpu" else requested

                            // ✅ 读取 manifest（你原本就有）
                            val manifest = modelManager.getNexaManifest(spec)

                            // ✅ modelName：优先用 manifest 里的 ModelName，否则 fallback
                            val modelName = manifest?.ModelName?.takeIf { it.isNotBlank() }
                                ?: spec.id // 或者写死 "omni-neural" 也行，但用 manifest 更稳

                            // ✅ 路径：entryPath 是你 model list 里的主文件（files-*.nexa）
                            val modelDir = modelManager.modelDir(spec)
                            val entryPath = modelManager.entryPath(spec)

                            // ✅ sanity check
                            val dir = File(modelDir.absolutePath)
                            val entry = File(entryPath)
                            require(dir.exists() && dir.isDirectory) { "Model dir missing: ${dir.absolutePath}" }
                            require(entry.exists() && entry.isFile && entry.length() > 0L) { "Model file missing/empty: ${entry.absolutePath}" }

                            Log.i("LifeLens", "createAndInitClient(): requested=$requested effective=$effectivePlugin")
                            Log.i("LifeLens", "createAndInitClient(): modelName=$modelName")
                            Log.i("LifeLens", "createAndInitClient(): modelDir=${dir.absolutePath}")
                            Log.i("LifeLens", "createAndInitClient(): modelFile=${entry.absolutePath} len=${entry.length()}")

                            val client = NexaVlmClient(
                                context = context,
                                modelName = modelName,
                                pluginId = effectivePlugin,
                                modelFilePath = entry.absolutePath,
                                modelDirPath = dir.absolutePath,
                                npuLibFolderPath = applicationInfo.nativeLibraryDir
                            )

                            // ✅ init() 返回 Result<Unit>，直接 getOrThrow()
                            client.init().getOrThrow()

                            client
                        }
                    }


                fun handleAskWithImage() {
                    val q = questionText.trim()
                    if (q.isBlank() || isProcessing) return

                    scope.launch {
                        isProcessing = true
                        streamingAnswer = ""

                        try {
                            val client = activeClient ?: error("Model not initialized. Tap Get Started first.")

                            // imagePath: uploaded first, otherwise capture
                            val imagePath: String = uploadedImagePath ?: run {
                                if (!(cameraGranted && cameraReady)) {
                                    error("No image yet. Please Upload Photo, or use Quick Test.")
                                }
                                headline = "Capturing..."
                                detail = q

                                val raw = File(context.cacheDir, "ask_capture_raw_${System.currentTimeMillis()}.jpg")
                                val captured = takePhoto(context, imageCapture, raw)
                                require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }

                                // ✅ prepare for VLM
                                val prepared = prepareImageForVlm(context, captured.absolutePath, maxSize = 448, squareCrop = true)
                                uploadedImagePath = prepared
                                prepared
                            }

                            val prompt = buildPrompt(audience, q)

                            headline = "Thinking..."
                            detail = q
                            Log.d("LifeLens", "Ask with image=$imagePath plugin=$pluginId")

                            // ✅ IMPORTANT: matches your NexaVlmClient signature
                            // generateWithImageStream(imagePath, prompt)
                            client.generateWithImageStream(imagePath, prompt).collect { token ->
                                streamingAnswer += token
                            }

                            headline = "Answer"
                            detail = q
                        } catch (t: Throwable) {
                            Log.e("LifeLens", "Ask failed", t)
                            headline = "Failed"
                            detail = t.message ?: "Unknown error"
                            if (streamingAnswer.isBlank()) streamingAnswer = "Error: ${t.message ?: "Unknown"}"
                        } finally {
                            isProcessing = false
                        }
                    }
                }

                fun quickTestDefaultPhoto() {
                    if (isProcessing) return

                    scope.launch {
                        isProcessing = true
                        runCatching {
                            val client = activeClient ?: error("Model not initialized. Tap Get Started first.")

                            headline = "Loading default image..."
                            detail = "Preparing test..."

                            val raw = copyAssetToCache(context, "default_test.jpg")
                            val prepared = prepareImageForVlm(context, raw, maxSize = 448, squareCrop = true)
                            uploadedImagePath = prepared

                            questionText = defaultQuestion(audience)
                            streamingAnswer = ""

                            val prompt = buildPrompt(audience, questionText)

                            headline = "Thinking..."
                            detail = questionText

                            client.generateWithImageStream(prepared, prompt).collect { token ->
                                streamingAnswer += token
                            }

                            headline = "Answer"
                            detail = questionText

                        }.onFailure {
                            headline = "Quick test failed"
                            detail = it.message ?: "Unknown error"
                        }
                        isProcessing = false
                    }
                }

                fun startSetup() {
                    if (setupRunning) return
                    phase = Phase.SETUP
                    setupError = null
                    setupRunning = true

                    scope.launch {
                        try {
                            // 1) check model
                            headline = "Checking model..."
                            detail = "Looking for local model files."
                            progress = null

                            val missing = modelManager.missingFiles(spec)
                            modelReady = missing.isEmpty()

                            // 2) download if missing
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

                            // 3) init: try cpu_gpu first, then npu
                            headline = "Initializing..."
                            detail = "Trying CPU/GPU…"
                            progress = null

                            val tryOrder = listOf("cpu_gpu", "npu")
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

                            // 4) camera
                            headline = "Almost ready"
                            detail = "We’ll ask for camera permission so you can capture."
                            progress = null

                            if (!cameraGranted) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            bindCamera()

                            // 5) ready
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

                Surface(modifier = Modifier.fillMaxSize()) {
                    when (phase) {
                        Phase.INTRO -> IntroScreen(
                            title = "LifeLens",
                            subtitle = "Understand what you see.\nMade for Elderly & Kids.",
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
                            uploadedImagePath = uploadedImagePath,
                            cameraReady = cameraReady,
                            hasUploadedImage = uploadedImagePath != null,
                            onRequestCamera = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            onBindCamera = { bindCamera() },
                            audience = audience,
                            onAudienceChange = {
                                audience = it
                                if (questionText.isBlank()) questionText = defaultQuestion(audience)
                            },
                            onUpload = { uploadLauncher.launch("image/*") },
                            onCapture = {
                                scope.launch {
                                    runCatching {
                                        if (!(cameraGranted && cameraReady)) error("Camera not ready")
                                        val raw = File(context.cacheDir, "capture_raw_${System.currentTimeMillis()}.jpg")
                                        val captured = takePhoto(context, imageCapture, raw)
                                        require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }

                                        val prepared = prepareImageForVlm(context, captured.absolutePath, maxSize = 448, squareCrop = true)
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

// ---------------- UI Composables ----------------

@Composable
private fun IntroScreen(
    title: String,
    subtitle: String,
    primaryText: String,
    onPrimary: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))

            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(primaryText)
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Tip: The first run downloads the on-device model.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SetupScreen(
    headline: String,
    detail: String,
    progress: Int?,
    errorText: String?,
    running: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium)

                    Spacer(Modifier.height(14.dp))
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress / 100f })
                        Spacer(Modifier.height(8.dp))
                        Text("$progress%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator()
                    }

                    if (errorText != null) {
                        Spacer(Modifier.height(14.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        Text("Error details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(errorText, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRetry,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Retry") }

                            OutlinedButton(
                                onClick = onBack,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Back") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyScreen(
    previewView: PreviewView,
    cameraGranted: Boolean,
    cameraReady: Boolean,
    hasUploadedImage: Boolean,
    uploadedImagePath: String?,
    onRequestCamera: () -> Unit,
    onBindCamera: () -> Unit,
    audience: Audience,
    onAudienceChange: (Audience) -> Unit,
    onUpload: () -> Unit,
    onCapture: () -> Unit,
    headline: String,
    detail: String,
    questionText: String,
    onQuestionTextChange: (String) -> Unit,
    isProcessing: Boolean,
    streamingAnswer: String,
    onAskSubmit: () -> Unit,
    onQuickTest: () -> Unit
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LifeLens",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = audience == Audience.ELDERLY,
                onClick = { onAudienceChange(Audience.ELDERLY) },
                label = { Text("Elderly") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = audience == Audience.CHILD,
                onClick = { onAudienceChange(Audience.CHILD) },
                label = { Text("Child") }
            )
        }

        Spacer(Modifier.height(12.dp))

        Card(shape = RoundedCornerShape(20.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

                if (!cameraGranted) {
                    OverlayHint(
                        title = "Camera permission needed",
                        subtitle = "You can still upload or use Quick Test.",
                        primary = "Grant",
                        onPrimary = onRequestCamera
                    )
                } else if (!cameraReady) {
                    OverlayHint(
                        title = "Camera not ready",
                        subtitle = "Try: Emulator → Settings → Camera → Webcam0",
                        primary = "Retry",
                        onPrimary = onBindCamera
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (hasUploadedImage && uploadedImagePath != null) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Loaded Image",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    val bitmap = remember(uploadedImagePath) {
                        BitmapFactory.decodeFile(uploadedImagePath)
                    }

                    if (bitmap == null) {
                        Text("Failed to decode image.", color = MaterialTheme.colorScheme.error)
                    } else {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Loaded image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onCapture,
                enabled = cameraGranted && cameraReady && !isProcessing,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Capture") }

            OutlinedButton(
                onClick = onUpload,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Upload Photo") }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = onQuickTest,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Quick Test (Default Photo)") }

        Spacer(Modifier.height(12.dp))

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp)) {

                OutlinedTextField(
                    value = questionText,
                    onValueChange = onQuestionTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What is this? Is it safe?") },
                    singleLine = true,
                    enabled = !isProcessing
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onAskSubmit,
                    enabled = questionText.isNotBlank() && !isProcessing &&
                            (hasUploadedImage || (cameraGranted && cameraReady)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ask") }

                if (isProcessing) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (streamingAnswer.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Answer", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(streamingAnswer)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(headline, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(detail)
            }
        }
    }
}

@Composable
private fun OverlayHint(
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onPrimary, shape = RoundedCornerShape(14.dp)) { Text(primary) }
            }
        }
    }
}

// ---------------- helpers ----------------

private fun defaultQuestion(audience: Audience): String {
    return when (audience) {
        Audience.ELDERLY -> "What is this object? What is it used for? Are there any safety concerns?"
        Audience.CHILD -> "What is this? What does it do? Is it safe to use?"
    }
}

private fun buildPrompt(audience: Audience, userQuestion: String): String {
    val system = when (audience) {
        Audience.ELDERLY -> """
You are LifeLens, an offline assistant designed for elderly users.

Rules:
- Use very simple English.
- Use short sentences.
- Explain what the object is.
- Explain what it is used for.
- Give 1–3 safety tips if needed.
- If unsure, say you are not certain and give safe advice.
""".trimIndent()

        Audience.CHILD -> """
You are LifeLens, an assistant for children (age 6–10).

Rules:
- Use friendly and simple language.
- Explain what the object is.
- Explain what it does.
- Add one fun fact if possible.
- Always mention safety if relevant.
""".trimIndent()
    }

    return system + "\n\nUser question: " + userQuestion.trim()
}

private suspend fun copyUriToFile(resolver: ContentResolver, uri: Uri, outFile: File) {
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

private suspend fun copyAssetToCache(context: android.content.Context, assetName: String): String =
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

/**
 * ✅ 把任意图片文件处理成 VLM 更稳定的输入：
 * - 修正 EXIF 旋转
 * - 缩放到 <= maxSize（默认 448）
 * - 可选：正方形 center-crop（更稳）
 * - 输出到 cacheDir，返回新的 absolutePath
 */
private suspend fun prepareImageForVlm(
    context: android.content.Context,
    srcPath: String,
    maxSize: Int = 448,
    squareCrop: Boolean = true
): String = withContext(Dispatchers.IO) {

    val srcFile = File(srcPath)
    require(srcFile.exists() && srcFile.length() > 0L) { "Image not found or empty: $srcPath" }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Failed to read image bounds: $srcPath" }

    val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSize, maxSize)
    val opts = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    var bmp = BitmapFactory.decodeFile(srcFile.absolutePath, opts)
        ?: throw IllegalStateException("Failed to decode image: $srcPath")

    val rotated = applyExifRotationIfNeeded(srcFile.absolutePath, bmp)
    if (rotated !== bmp) {
        bmp.recycle()
        bmp = rotated
    }

    val scaled = scaleDownToMax(bmp, maxSize)
    if (scaled !== bmp) {
        bmp.recycle()
        bmp = scaled
    }

    val finalBmp = if (squareCrop) {
        val cropped = centerCropSquare(bmp)
        if (cropped !== bmp) bmp.recycle()
        cropped
    } else bmp

    val outFile = File(context.cacheDir, "vlm_${System.currentTimeMillis()}.jpg")
    FileOutputStream(outFile).use { fos ->
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
    }
    finalBmp.recycle()

    require(outFile.exists() && outFile.length() > 0L) { "Prepared image is empty" }
    outFile.absolutePath
}

private fun computeInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
    var inSampleSize = 1
    val halfW = w / 2
    val halfH = h / 2
    while (halfW / inSampleSize >= reqW && halfH / inSampleSize >= reqH) {
        inSampleSize *= 2
    }
    return inSampleSize.coerceAtLeast(1)
}

private fun applyExifRotationIfNeeded(path: String, bitmap: Bitmap): Bitmap {
    return try {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) bitmap
        else {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
    } catch (_: Exception) {
        bitmap
    }
}

private fun scaleDownToMax(src: Bitmap, maxSize: Int): Bitmap {
    val w = src.width
    val h = src.height
    val maxSide = maxOf(w, h)
    if (maxSide <= maxSize) return src
    val scale = maxSize.toFloat() / maxSide.toFloat()
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, nw, nh, true)
}

private fun centerCropSquare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val side = minOf(w, h)
    val x = (w - side) / 2
    val y = (h - side) / 2
    return if (x == 0 && y == 0 && side == w && side == h) src
    else Bitmap.createBitmap(src, x, y, side, side)
}
