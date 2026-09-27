    package com.example.talkbackvision
    
    import android.Manifest
    import android.content.pm.PackageManager
    import android.graphics.Bitmap
    import android.graphics.BitmapFactory
    import android.graphics.Matrix
    import android.os.Build
    import android.os.Bundle
    import android.speech.tts.TextToSpeech
    import android.util.Log
    import android.view.accessibility.AccessibilityEvent
    import android.view.accessibility.AccessibilityManager
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.rememberLauncherForActivityResult
    import androidx.activity.compose.setContent
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.annotation.OptIn
    import androidx.camera.core.CameraSelector
    import androidx.camera.core.ExperimentalGetImage
    import androidx.camera.core.ImageCapture
    import androidx.camera.core.ImageCaptureException
    import androidx.camera.core.ImageProxy
    import androidx.camera.core.Preview
    import androidx.camera.lifecycle.ProcessCameraProvider
    import androidx.camera.view.PreviewView
    import androidx.compose.foundation.layout.*
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.semantics.contentDescription
    import androidx.compose.ui.semantics.semantics
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.compose.ui.viewinterop.AndroidView
    import androidx.core.content.ContextCompat
    import androidx.lifecycle.compose.LocalLifecycleOwner
    import com.google.genai.Client
    import com.google.genai.types.Content
    import com.google.genai.types.Part
    import com.google.mlkit.vision.common.InputImage
    import com.google.mlkit.vision.text.TextRecognition
    import com.google.mlkit.vision.text.latin.TextRecognizerOptions
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import java.io.ByteArrayOutputStream
    import java.nio.ByteBuffer
    import java.util.Locale
    import java.util.concurrent.ExecutorService
    import java.util.concurrent.Executors
    
    // ============================================================
    // GEMINI
    // ============================================================
    // IMPORTANTE:
    // Coloca aquí una NUEVA API Key.
    // No uses la clave que compartiste anteriormente.
    private const val GEMINI_API_KEY = "AQ.Ab8RN6JTrc_aZGvcb92OEMzoFfeTKSrKQ8WPgbPN2qSudBPvgA"
    
    class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    
        private lateinit var tts: TextToSpeech
        private lateinit var cameraExecutor: ExecutorService
    
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
    
            tts = TextToSpeech(this, this)
            cameraExecutor = Executors.newSingleThreadExecutor()
    
            setContent {
                TalkBackVisionApp(
                    onSpeak = { text ->
                        speakText(text)
                    }
                )
            }
        }
    
        // ============================================================
        // TEXT TO SPEECH
        // ============================================================
    
        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
    
                val locale = Locale.Builder()
                    .setLanguage("es")
                    .setRegion("ES")
                    .build()
    
                val result = tts.setLanguage(locale)
    
                if (
                    result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(
                        "TTS",
                        "El idioma español no está soportado."
                    )
                }
    
            } else {
                Log.e(
                    "TTS",
                    "Error al inicializar TextToSpeech."
                )
            }
        }
    
        private fun speakText(text: String) {
    
            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
    
            val accessibilityManager =
                getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
    
            if (accessibilityManager.isEnabled) {
    
                val event =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AccessibilityEvent()
                    } else {
                        @Suppress("DEPRECATION")
                        AccessibilityEvent.obtain()
                    }
    
                @Suppress("DEPRECATION")
                event.eventType =
                    AccessibilityEvent.TYPE_ANNOUNCEMENT
    
                event.text.add(text)
    
                accessibilityManager.sendAccessibilityEvent(event)
            }
        }
    
        override fun onDestroy() {
    
            if (::tts.isInitialized) {
                tts.stop()
                tts.shutdown()
            }
    
            cameraExecutor.shutdown()
    
            super.onDestroy()
        }
    }
    
    // ============================================================
    // APLICACIÓN PRINCIPAL
    // ============================================================
    
    @Composable
    fun TalkBackVisionApp(
        onSpeak: (String) -> Unit
    ) {
    
        var currentScreen by remember {
            mutableStateOf("home")
        }
    
        val context = LocalContext.current
    
        var hasCameraPermission by remember {
    
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
    
        val launcher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
    
                    hasCameraPermission = isGranted
    
                    if (isGranted) {
                        currentScreen = "camera"
                    }
                }
            )
    
        MaterialTheme {
    
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
    
                when (currentScreen) {
    
                    "home" -> {
    
                        HomeScreen(
                            onAnalyzeEnvironmentClick = {
    
                                if (hasCameraPermission) {
    
                                    currentScreen = "camera"
    
                                } else {
    
                                    launcher.launch(
                                        Manifest.permission.CAMERA
                                    )
                                }
                            }
                        )
                    }
    
                    "camera" -> {
    
                        CameraScreen(
                            onBackClick = {
                                currentScreen = "home"
                            },
                            onSpeak = onSpeak
                        )
                    }
                }
            }
        }
    }
    
    // ============================================================
    // PANTALLA PRINCIPAL
    // ============================================================
    
    @Composable
    fun HomeScreen(
        onAnalyzeEnvironmentClick: () -> Unit
    ) {
    
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
    
            horizontalAlignment =
                Alignment.CenterHorizontally,
    
            verticalArrangement =
                Arrangement.Center
        ) {
    
            Text(
                text = "TalkBack Vision",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
    
            Spacer(
                modifier = Modifier.height(12.dp)
            )
    
            Text(
                text =
                    "Sistema de asistencia visual inteligente",
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
    
            Spacer(
                modifier = Modifier.height(40.dp)
            )
    
            Button(
                onClick = onAnalyzeEnvironmentClick,
    
                modifier = Modifier
                    .fillMaxWidth()
                    .height(65.dp)
                    .semantics {
    
                        contentDescription =
                            "Analizar entorno. Abre la cámara para interpretar la escena."
                    }
            ) {
    
                Text(
                    text = "📷  Analizar entorno",
                    fontSize = 18.sp
                )
            }
    
            Spacer(
                modifier = Modifier.height(20.dp)
            )
    
            Button(
    
                onClick = { },
    
                modifier = Modifier
                    .fillMaxWidth()
                    .height(65.dp)
                    .semantics {
    
                        contentDescription =
                            "Analizar pantalla. Analiza los elementos visuales de la pantalla."
                    }
            ) {
    
                Text(
                    text = "📱  Analizar pantalla",
                    fontSize = 18.sp
                )
            }
    
            Spacer(
                modifier = Modifier.height(20.dp)
            )
    
            Button(
    
                onClick = { },
    
                modifier = Modifier
                    .fillMaxWidth()
                    .height(65.dp)
                    .semantics {
    
                        contentDescription =
                            "Configuración. Abre las opciones de TalkBack Vision."
                    }
            ) {
    
                Text(
                    text = "⚙️  Configuración",
                    fontSize = 18.sp
                )
            }
    
            Spacer(
                modifier = Modifier.height(40.dp)
            )
    
            Text(
                text =
                    "Diseñado para apoyar la autonomía y accesibilidad.",
    
                fontSize = 14.sp,
    
                textAlign =
                    TextAlign.Center
            )
        }
    }
    
    // ============================================================
    // PANTALLA DE CÁMARA
    // ============================================================
    
    @OptIn(ExperimentalGetImage::class)
    @Composable
    fun CameraScreen(
        onBackClick: () -> Unit,
        onSpeak: (String) -> Unit
    ) {
    
        val context = LocalContext.current
    
        val lifecycleOwner =
            LocalLifecycleOwner.current
    
        var imageCapture: ImageCapture? by remember {
            mutableStateOf(null)
        }
    
        var detectedText by remember {
    
            mutableStateOf(
                "Elige una opción para interpretar el entorno."
            )
        }
    
        var isProcessing by remember {
            mutableStateOf(false)
        }
    
        // ========================================================
        // CLIENTE GEMINI
        // ========================================================
    
        val geminiClient = remember {
    
            Client.builder()
                .apiKey(GEMINI_API_KEY)
                .build()
        }
    
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
    
            // ====================================================
            // CAMERA PREVIEW
            // ====================================================
    
            AndroidView(
    
                factory = { ctx ->
    
                    val previewView =
                        PreviewView(ctx)
    
                    val cameraProviderFuture =
                        ProcessCameraProvider.getInstance(ctx)
    
                    cameraProviderFuture.addListener({
    
                        val cameraProvider =
                            cameraProviderFuture.get()
    
                        val preview =
                            Preview.Builder()
                                .build()
                                .also {
    
                                    it.setSurfaceProvider(
                                        previewView.surfaceProvider
                                    )
                                }
    
                        imageCapture =
                            ImageCapture.Builder()
                                .setCaptureMode(
                                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                )
                                .build()
    
                        val cameraSelector =
                            CameraSelector.DEFAULT_BACK_CAMERA
    
                        try {
    
                            cameraProvider.unbindAll()
    
                            cameraProvider.bindToLifecycle(
    
                                lifecycleOwner,
    
                                cameraSelector,
    
                                preview,
    
                                imageCapture
                            )
    
                        } catch (e: Exception) {
    
                            Log.e(
                                "CameraScreen",
                                "Error al vincular cámara: ${e.message}",
                                e
                            )
                        }
    
                    }, ContextCompat.getMainExecutor(ctx))
    
                    previewView
                },
    
                modifier =
                    Modifier.fillMaxSize()
            )
    
            // ====================================================
            // CONTROLES
            // ====================================================
    
            Column(
    
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
    
                verticalArrangement =
                    Arrangement.SpaceBetween,
    
                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {
    
                // =================================================
                // BOTÓN VOLVER
                // =================================================
    
                Button(
    
                    onClick = onBackClick,
    
                    modifier = Modifier
                        .align(Alignment.Start)
                        .semantics {
    
                            contentDescription =
                                "Regresar a la pantalla principal"
                        }
                ) {
    
                    Text("⬅️ Volver")
                }
    
                // =================================================
                // RESULTADO
                // =================================================
    
                Card(
    
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
    
                    colors =
                        CardDefaults.cardColors(
    
                            containerColor =
                                MaterialTheme
                                    .colorScheme
                                    .surface
                                    .copy(alpha = 0.85f)
                        )
                ) {
    
                    Text(
    
                        text = detectedText,
    
                        fontSize = 16.sp,
    
                        modifier =
                            Modifier.padding(16.dp),
    
                        textAlign =
                            TextAlign.Center
                    )
                }
    
                // =================================================
                // BOTONES
                // =================================================
    
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
    
                    // =================================================
                    // GEMINI - DESCRIBIR ESCENA
                    // =================================================
    
                    Button(
    
                        onClick = {
    
                            val capture =
                                imageCapture
                                    ?: return@Button
    
                            isProcessing = true
    
                            detectedText =
                                "Analizando la escena con IA..."
    
                            onSpeak(
                                "Analizando la escena con inteligencia artificial..."
                            )
    
                            capture.takePicture(
    
                                ContextCompat.getMainExecutor(
                                    context
                                ),
    
                                object :
                                    ImageCapture.OnImageCapturedCallback() {
    
                                    override fun onCaptureSuccess(
                                        imageProxy: ImageProxy
                                    ) {
    
                                        val bitmap =
                                            imageProxyToBitmap(
                                                imageProxy
                                            )
    
                                        imageProxy.close()
    
                                        if (bitmap != null) {
    
                                            CoroutineScope(
                                                Dispatchers.IO
                                            ).launch {
    
                                                try {
    
                                                    // ====================================
                                                    // PROMPT
                                                    // ====================================
    
                                                    val prompt =
                                                        """
                                                        Eres un asistente visual para una persona con discapacidad visual.
    
                                                        Analiza cuidadosamente la imagen.
    
                                                        Describe de forma breve, clara y directa:
    
                                                        - Objetos principales.
                                                        - Personas, si existen.
                                                        - Obstáculos importantes.
                                                        - Ubicación aproximada de los objetos.
                                                        - Elementos que puedan ser relevantes para la seguridad.
    
                                                        No inventes información que no puedas observar.
    
                                                        Responde en español y utiliza máximo 3 oraciones.
                                                        """.trimIndent()
    
                                                    // ====================================
                                                    // CONVERTIR BITMAP A JPEG
                                                    // ====================================
    
                                                    val imageBytes =
                                                        bitmapToJpegBytes(
                                                            bitmap
                                                        )
    
                                                    // ====================================
                                                    // CONTENIDO MULTIMODAL
                                                    // ====================================
    
                                                    val content =
                                                        Content.fromParts(
    
                                                            Part.fromBytes(
                                                                imageBytes,
                                                                "image/jpeg"
                                                            ),
    
                                                            Part.fromText(
                                                                prompt
                                                            )
                                                        )
    
                                                    // ====================================
                                                    // GEMINI
                                                    // ====================================
    
                                                    val response =
                                                        geminiClient
                                                            .models
                                                            .generateContent(
    
                                                                "gemini-2.5-flash",
    
                                                                content,
    
                                                                null
                                                            )
    
                                                    val resultText =
                                                        response
                                                            .text()
                                                            ?: "No se pudo interpretar la escena."
    
                                                    // ====================================
                                                    // ACTUALIZAR INTERFAZ
                                                    // ====================================
    
                                                    withContext(
                                                        Dispatchers.Main
                                                    ) {
    
                                                        isProcessing =
                                                            false
    
                                                        detectedText =
                                                            resultText
    
                                                        onSpeak(
                                                            resultText
                                                        )
                                                    }
    
                                                } catch (
                                                    e: Exception
                                                ) {
    
                                                    Log.e(
                                                        "Gemini",
                                                        "Error al analizar imagen",
                                                        e
                                                    )
    
                                                    withContext(
                                                        Dispatchers.Main
                                                    ) {
    
                                                        isProcessing =
                                                            false
    
                                                        detectedText =
                                                            "Error al conectar con el asistente de IA."
    
                                                        onSpeak(
                                                            "Error al analizar la escena."
                                                        )
                                                    }
                                                }
                                            }
    
                                        } else {
    
                                            isProcessing =
                                                false
    
                                            detectedText =
                                                "No se pudo procesar la imagen."
    
                                            onSpeak(
                                                "No se pudo procesar la imagen."
                                            )
                                        }
                                    }
    
                                    override fun onError(
                                        exception:
                                        ImageCaptureException
                                    ) {
    
                                        isProcessing =
                                            false
    
                                        detectedText =
                                            "Error al capturar la imagen."
    
                                        onSpeak(
                                            "Error al tomar la foto."
                                        )
    
                                        Log.e(
                                            "CameraScreen",
                                            "Error de captura",
                                            exception
                                        )
                                    }
                                }
                            )
                        },
    
                        enabled =
                            !isProcessing,
    
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .semantics {
    
                                contentDescription =
                                    "Describir escena completa con Inteligencia Artificial"
                            }
                    ) {
    
                        Text(
    
                            text =
                                if (isProcessing)
                                    "⏳ Analizando..."
                                else
                                    "👁️ Describir Escena Completa",
    
                            fontSize = 18.sp
                        )
                    }
    
                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )
    
                    // =================================================
                    // OCR - ML KIT
                    // =================================================
    
                    OutlinedButton(
    
                        onClick = {
    
                            val capture =
                                imageCapture
                                    ?: return@OutlinedButton
    
                            isProcessing =
                                true
    
                            detectedText =
                                "Buscando texto..."
    
                            onSpeak(
                                "Buscando texto en la imagen..."
                            )
    
                            capture.takePicture(
    
                                ContextCompat.getMainExecutor(
                                    context
                                ),
    
                                object :
                                    ImageCapture.OnImageCapturedCallback() {
    
                                    override fun onCaptureSuccess(
                                        imageProxy: ImageProxy
                                    ) {
    
                                        val mediaImage =
                                            imageProxy.image
    
                                        if (mediaImage != null) {
    
                                            val inputImage =
                                                InputImage.fromMediaImage(
    
                                                    mediaImage,
    
                                                    imageProxy
                                                        .imageInfo
                                                        .rotationDegrees
                                                )
    
                                            val recognizer =
                                                TextRecognition
                                                    .getClient(
                                                        TextRecognizerOptions
                                                            .DEFAULT_OPTIONS
                                                    )
    
                                            recognizer
                                                .process(
                                                    inputImage
                                                )
                                                .addOnSuccessListener {
    
                                                        visionText ->
    
                                                    isProcessing =
                                                        false
    
                                                    val result =
                                                        visionText.text
    
                                                    if (
                                                        result.isNotBlank()
                                                    ) {
    
                                                        detectedText =
                                                            result
    
                                                        onSpeak(
                                                            "Texto detectado: $result"
                                                        )
    
                                                    } else {
    
                                                        detectedText =
                                                            "No se encontró texto."
    
                                                        onSpeak(
                                                            "No se encontró texto en la imagen."
                                                        )
                                                    }
                                                }
                                                .addOnFailureListener {
    
                                                    isProcessing =
                                                        false
    
                                                    detectedText =
                                                        "Error al leer el texto."
    
                                                    onSpeak(
                                                        "Error al analizar el texto."
                                                    )
    
                                                    Log.e(
                                                        "OCR",
                                                        "Error al reconocer texto",
                                                        it
                                                    )
                                                }
                                                .addOnCompleteListener {
    
                                                    imageProxy.close()
                                                }
    
                                        } else {
    
                                            imageProxy.close()
    
                                            isProcessing =
                                                false
    
                                            detectedText =
                                                "No se pudo obtener la imagen."
    
                                            onSpeak(
                                                "No se pudo obtener la imagen."
                                            )
                                        }
                                    }
    
                                    override fun onError(
                                        exception:
                                        ImageCaptureException
                                    ) {
    
                                        isProcessing =
                                            false
    
                                        detectedText =
                                            "Error al capturar la imagen."
    
                                        onSpeak(
                                            "Error al tomar la foto."
                                        )
    
                                        Log.e(
                                            "OCR",
                                            "Error de captura",
                                            exception
                                        )
                                    }
                                }
                            )
                        },
    
                        enabled =
                            !isProcessing,
    
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics {
    
                                contentDescription =
                                    "Solo leer texto local con OCR"
                            }
                    ) {
    
                        Text(
                            text =
                                "📄 Solo leer texto (OCR)"
                        )
                    }
                }
            }
        }
    }
    
    // ============================================================
    // CONVERTIR IMAGEPROXY A BITMAP
    // ============================================================
    
    private fun imageProxyToBitmap(
        image: ImageProxy
    ): Bitmap? {
    
        val buffer: ByteBuffer =
            image.planes[0].buffer
    
        val bytes =
            ByteArray(buffer.remaining())
    
        buffer.get(bytes)
    
        val bitmap =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            )
                ?: return null
    
        val rotationDegrees =
            image.imageInfo.rotationDegrees
    
        return if (rotationDegrees != 0) {
    
            val matrix =
                Matrix().apply {
    
                    postRotate(
                        rotationDegrees.toFloat()
                    )
                }
    
            Bitmap.createBitmap(
    
                bitmap,
    
                0,
                0,
    
                bitmap.width,
                bitmap.height,
    
                matrix,
    
                true
            )
    
        } else {
    
            bitmap
        }
    }
    
    // ============================================================
    // CONVERTIR BITMAP A JPEG
    // ============================================================
    
    private fun bitmapToJpegBytes(
        bitmap: Bitmap
    ): ByteArray {
    
        val outputStream =
            ByteArrayOutputStream()
    
        bitmap.compress(
    
            Bitmap.CompressFormat.JPEG,
    
            85,
    
            outputStream
        )
    
        return outputStream.toByteArray()
    }