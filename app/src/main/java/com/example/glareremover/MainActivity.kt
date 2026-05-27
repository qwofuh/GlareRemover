package com.example.glareremover

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.glareremover.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    // OpenCV + MediaPipe обрабатываются в отдельном диспетчере
    private val cvDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var faceLandmarker: FaceLandmarker? = null

    // Последние landmarks — AtomicReference безопасен между потоками без synchronized
    private val latestLandmarks = AtomicReference<List<PointF?>?>(null)

    // Флаг: идёт ли сейчас обработка кадра (не ставим в очередь лишние кадры)
    private val isProcessing = AtomicBoolean(false)

    // GlareDetector и Processor создаём один раз, threshold регулируется из UI
    private lateinit var glareDetector: GlareDetector
    private lateinit var glareProcessor: GlareProcessor

    // Настройки из UI
    @Volatile private var glareRemovalEnabled = true
    @Volatile private var showMaskMode = false

    // SeekBar: 0..100 → threshold: 150..255
    private var currentThreshold: Int = 200

    companion object {
        private const val TAG = "GlareRemover"
        private const val REQUEST_CODE_CAMERA = 100
        private const val MIN_THRESHOLD = 150
        private const val MAX_THRESHOLD = 255
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── OpenCV инициализация ──────────────────────────────────────────────
        // initLocal() — правильный метод для Maven-артефакта opencv-android 4.x
        // (initDebug() устарел и не работает без OpenCV Manager)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV init failed!")
            binding.tvStats.text = "❌ OpenCV не инициализирован"
            return
        }
        Log.d(TAG, "OpenCV OK: ${OpenCVLoader.OPENCV_VERSION}")

        glareDetector  = GlareDetector(brightnessThreshold = currentThreshold)
        glareProcessor = GlareProcessor(glareDetector)

        // Executor для камеры — создаём ДО startCamera
        cameraExecutor = Executors.newSingleThreadExecutor()

        initFaceLandmarker()
        setupControls()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cvDispatcher.close()
        faceLandmarker?.close()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.switchGlareRemoval.setOnCheckedChangeListener { _, isChecked ->
            glareRemovalEnabled = isChecked
            binding.overlayView.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) binding.overlayView.updateFrame(null, emptyList())
        }

        binding.switchShowMask.setOnCheckedChangeListener { _, isChecked ->
            showMaskMode = isChecked
            binding.overlayView.displayMode = if (isChecked)
                GlareOverlayView.DisplayMode.OVERLAY_ON_PREVIEW
            else
                GlareOverlayView.DisplayMode.PROCESSED_FRAME
        }

        // SeekBar: progress 0..100 → threshold 150..255
        binding.seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                currentThreshold = MIN_THRESHOLD + (progress / 100f * (MAX_THRESHOLD - MIN_THRESHOLD)).toInt()
                glareDetector.brightnessThreshold = currentThreshold
                binding.tvThresholdValue.text = currentThreshold.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Начальное значение
        binding.tvThresholdValue.text = currentThreshold.toString()
        binding.seekBarThreshold.progress = 50
    }

    // ── MediaPipe ─────────────────────────────────────────────────────────────

    private fun initFaceLandmarker() {
        try {
            // Файл face_landmarker.task нужно положить в app/src/main/assets/
            // Скачать: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result: FaceLandmarkerResult, _ ->
                    onFaceLandmarks(result)
                }
                .setErrorListener { err ->
                    Log.e(TAG, "FaceLandmarker error: ${err.message}")
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d(TAG, "FaceLandmarker initialized")
        } catch (e: Exception) {
            Log.e(TAG, "FaceLandmarker init failed: ${e.message}", e)
            runOnUiThread {
                binding.tvStats.text = "❌ face_landmarker.task не найден в assets/"
            }
        }
    }

    private fun onFaceLandmarks(result: FaceLandmarkerResult) {
        val face = result.faceLandmarks()?.firstOrNull()
        latestLandmarks.set(
            if (face != null && face.size >= 468)
                face.map { PointF(it.x(), it.y()) }
            else
                null
        )
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Запрашиваем YUV_420_888 явно
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analyzer
                )
                Log.d(TAG, "Camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Frame processing ──────────────────────────────────────────────────────

    /**
     * Вызывается из cameraExecutor на каждый кадр.
     * Если предыдущая обработка ещё не завершена — кадр отбрасываем (isProcessing guard).
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toCorrectBitmap()
        imageProxy.close() // закрываем сразу, не держим ресурс камеры

        if (bitmap == null) return

        // Отправляем в MediaPipe для детекции (асинхронно)
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            faceLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync error: ${e.message}")
        }

        if (!glareRemovalEnabled) return

        // Пропускаем кадр если предыдущий ещё обрабатывается
        if (!isProcessing.compareAndSet(false, true)) return

        val landmarks = latestLandmarks.get()

        // Обработка в отдельном корутин-контексте (OpenCV-тред)
        lifecycleScope.launch(cvDispatcher) {
            try {
                val result = if (landmarks != null && landmarks.size >= 468) {
                    glareProcessor.process(bitmap, landmarks)
                } else {
                    null
                }

                withContext(Dispatchers.Main) {
                    if (result != null) {
                        if (showMaskMode) {
                            // Режим маски: рисуем контуры поверх живого PreviewView
                            binding.overlayView.updateFrame(null, result.glareRegions)
                        } else {
                            // Режим замены: показываем обработанный кадр
                            binding.overlayView.updateFrame(result.processedBitmap, emptyList())
                        }
                        updateStats(landmarks != null, result.glareCount, result.processingMs)
                    } else {
                        binding.overlayView.updateFrame(null, emptyList())
                        updateStats(false, 0, 0)
                    }
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun updateStats(faceDetected: Boolean, glareCount: Int, ms: Long) {
        val faceStr  = if (faceDetected) "✅ Лицо" else "❌ Лицо"
        val glareStr = if (glareCount > 0) "⚡ Бликов: $glareCount" else "✓ Бликов нет"
        val timeStr  = if (ms > 0) "${ms}ms" else "—"
        binding.tvStats.text = "$faceStr | $glareStr | $timeStr"
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    // ── YUV → Bitmap (исправленный) ───────────────────────────────────────────

    /**
     * Правильная конвертация YUV_420_888 → NV21 → JPEG → Bitmap.
     *
     * Ключевые исправления по сравнению с исходным кодом:
     *   1. Учитываем pixelStride и rowStride для плоскостей U и V —
     *      они НЕ обязательно плотно упакованы (pixelStride может быть 2).
     *   2. NV21 = Y..., затем V, U чередуются (VUVUVU...).
     *      В оригинале U и V были перепутаны местами, что давало неправильный цвет.
     *   3. Качество JPEG = 85 — баланс скорость/качество.
     */
    private fun ImageProxy.toCorrectBitmap(): Bitmap? {
        val image = this.image ?: return null
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val ySize = yBuf.remaining()
            val w = this.width
            val h = this.height

            // NV21: Y-плоскость (w×h), затем VU чередование (w×h/2 байт)
            val nv21 = ByteArray(ySize + w * h / 2)

            // Копируем Y (всегда pixelStride=1, rowStride может быть > width)
            val yRowStride = yPlane.rowStride
            if (yRowStride == w) {
                yBuf.get(nv21, 0, ySize)
            } else {
                // Убираем padding в конце каждой строки
                var offset = 0
                for (row in 0 until h) {
                    yBuf.position(row * yRowStride)
                    yBuf.get(nv21, offset, w)
                    offset += w
                }
            }

            // Собираем VU чередование вручную (pixelStride=2 означает VUVU уже в буфере,
            // но rowStride может добавлять padding)
            val vRowStride    = vPlane.rowStride
            val vPixelStride  = vPlane.pixelStride
            val uvHeight      = h / 2
            val uvWidth       = w / 2
            var uvOffset      = ySize

            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vIndex = row * vRowStride + col * vPixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    // NV21: сначала V, потом U
                    nv21[uvOffset++] = vBuf.get(vIndex)
                    nv21[uvOffset++] = uBuf.get(uIndex)
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 85, out)
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "toCorrectBitmap failed: ${e.message}")
            null
        }
    }
}