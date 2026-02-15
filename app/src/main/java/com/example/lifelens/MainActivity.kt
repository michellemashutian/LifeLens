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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.lifelens.camera.takePhoto
import com.example.lifelens.nexa.ModelManager
import com.example.lifelens.nexa.NexaVlmClient
import com.example.lifelens.tool.Audience
import com.example.lifelens.ui.theme.LifeLensTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.graphics.asImageBitmap


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
                // default plugin: emulator -> cpu, device -> npu
                //var pluginId by remember { mutableStateOf(if (isEmulator) "cpu" else "npu") }

                var pluginId by remember { mutableStateOf("npu") }
//                var pluginId by remember { mutableStateOf("cpu_gpu") }

                // camera
                val previewView = remember { PreviewView(context) }
                val imageCapture = remember { ImageCapture.Builder().build() }
                var cameraReady by remember { mutableStateOf(false) }

                // model
                val modelManager = remember { ModelManager(context) }
                val spec = remember { modelManager.defaultSpec() }
                val modelPath by remember(spec.id) { mutableStateOf(modelManager.entryPath(spec)) }
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

                // Upload photo -> copy to cache -> store absolute path
                val uploadLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        runCatching {
                            headline = "Loading image..."
                            detail = "Copying uploaded photo."
                            progress = null

                            val outFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                            copyUriToFile(context.contentResolver, uri, outFile)
                            require(outFile.exists() && outFile.length() > 0L) { "Uploaded file is empty" }

                            uploadedImagePath = outFile.absolutePath

                            if (questionText.isBlank()) {
                                questionText = defaultQuestion(audience)
                            }

                            headline = "Image ready"
                            detail = "Tap Send or Quick Test."
                            Log.d("LifeLens", "Uploaded image: ${outFile.absolutePath} (${outFile.length()} bytes)")
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
                            // ✅ 只允许 npu / cpu_gpu
                            val plugin = pid.trim().lowercase()
                            require(plugin == "npu" || plugin == "cpu_gpu") {
                                "Invalid pluginId=$pid. Use \"npu\" or \"cpu_gpu\"."
                            }

                            // ✅ entry file：files-1-1.nexa
                            val entryPath = modelManager.entryPath(spec)
                            val entry = File(entryPath)

                            Log.d("LifeLens", "createAndInitClient(plugin=$plugin)")
                            Log.d(
                                "LifeLens",
                                "entryPath=$entryPath exists=${entry.exists()} isFile=${entry.isFile} len=${entry.length()}"
                            )

                            require(entry.exists() && entry.isFile && entry.length() > 0L) {
                                "Model entry file missing: ${entry.absolutePath}"
                            }

                            val c = NexaVlmClient(
                                context = context,
                                modelPath = entry.absolutePath,   // ✅ 只传 entry 文件
                                pluginId = plugin                // ✅ "npu" or "cpu_gpu"
                            )

                            c.init()
                            c
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

                                val photoFile = File(context.cacheDir, "ask_capture_${System.currentTimeMillis()}.jpg")
                                val captured = takePhoto(context, imageCapture, photoFile)
                                require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }
                                captured.absolutePath
                            }

                            val prompt = buildPrompt(audience, q)

                            headline = "Thinking..."
                            detail = q
                            Log.d("LifeLens", "Ask with image=$imagePath plugin=$pluginId")

                            client.generateWithImageStream(prompt, imagePath).collect { token ->
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

                            val path = copyAssetToCache(context, "default_test.jpg")
                            uploadedImagePath = path

                            questionText = defaultQuestion(audience)

                            streamingAnswer = ""

                            val prompt = buildPrompt(audience, questionText)

                            headline = "Thinking..."
                            detail = questionText

                            client.generateWithImageStream(prompt, path).collect { token ->
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

                            // 3) init
                            headline = "Initializing..."
                            detail = if (pluginId == "npu") "Starting on NPU…" else "Starting on CPU/GPU…"
                            progress = null

                            val r = createAndInitClient(pluginId)
                            if (r.isSuccess) {
                                runCatching { activeClient?.destroy() }
                                activeClient = r.getOrThrow()
                            } else {
                                throw r.exceptionOrNull() ?: RuntimeException("Init failed (unknown)")
                            }

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
                        Phase.INTRO -> {
                            IntroScreen(
                                title = "LifeLens",
                                subtitle = "Understand what you see.\nMade for Elderly & Kids.",
                                primaryText = if (setupRunning) "Starting…" else "Get Started",
                                onPrimary = { startSetup() }
                            )

                        }

                        Phase.SETUP -> {
                            SetupScreen(
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
                        }

                        Phase.READY -> {
                            ReadyScreen(
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
                                    // if user hasn't typed anything, update default question when switching audience
                                    if (questionText.isBlank()) questionText = defaultQuestion(audience)
                                },
                                onUpload = { uploadLauncher.launch("image/*") },
                                onCapture = {
                                    scope.launch {
                                        runCatching {
                                            if (!(cameraGranted && cameraReady)) error("Camera not ready")
                                            val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                                            val captured = takePhoto(context, imageCapture, photoFile)
                                            require(captured.exists() && captured.length() > 0L) { "Captured image is empty" }
                                            uploadedImagePath = captured.absolutePath
                                            if (questionText.isBlank()) questionText = defaultQuestion(audience)
                                            headline = "Image ready"
                                            detail = "Type a question and tap Send."
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
                                onQuickTest = {
                                    // mark processing here to prevent double taps
                                    quickTestDefaultPhoto()
                                }
                            )
                        }
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

        // Camera Preview
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

// ✅ Loaded Image Preview
        if (hasUploadedImage && uploadedImagePath != null) {

            val f = remember(uploadedImagePath) { File(uploadedImagePath) }

            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp)) {

                    Text(
                        "Loaded Image",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Photo loaded",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))

                    val bitmap = remember(uploadedImagePath) {
                        android.graphics.BitmapFactory.decodeFile(uploadedImagePath)
                    }

                    if (bitmap == null) {
                        Text(
                            "Failed to decode image.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
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
                ) {
                    Text("Ask")
                }

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
        Audience.ELDERLY ->
            "What is this object? What is it used for? Are there any safety concerns?"
        Audience.CHILD ->
            "What is this? What does it do? Is it safe to use?"
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
