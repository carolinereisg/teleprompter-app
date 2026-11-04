package com.serenidadelab.teleprompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var previewView: PreviewView
    private lateinit var scrollView: ScrollView
    private lateinit var prompterText: TextView
    private lateinit var guideLine: View
    private lateinit var timerText: TextView
    private lateinit var gestureIndicator: TextView
    private lateinit var settingsText: TextView
    private lateinit var countdownText: TextView
    private lateinit var recordBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var playTextBtn: Button
    private lateinit var flipBtn: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isPaused = false
    private var isFinalizing = false
    private var useFront = true
    private var limitSec = 0

    private var detector: OpenPalmDetector? = null
    private lateinit var analysisExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Rolagem automática ----------
    private var scrolling = false
    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scrolling) return
            if (lastFrameNanos != 0L) {
                val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                scrollRemainder += pixelsPerSecond() * dt
                val dy = scrollRemainder.toInt()
                if (dy != 0) {
                    scrollView.scrollBy(0, dy)
                    scrollRemainder -= dy
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Velocidade 1–20 → 10 a 200 dp por segundo. */
    private fun pixelsPerSecond(): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, prefs.speed * 10f, resources.displayMetrics
        )

    private fun startScroll() {
        if (scrolling) return
        scrolling = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        playTextBtn.text = "⏸ Texto"
    }

    private fun stopScroll() {
        scrolling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        playTextBtn.text = "▶ Texto"
    }

    // ---------- Ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        requestedOrientation = if (prefs.landscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_record)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        previewView = findViewById(R.id.previewView)
        scrollView = findViewById(R.id.prompterScroll)
        prompterText = findViewById(R.id.prompterText)
        guideLine = findViewById(R.id.guideLine)
        timerText = findViewById(R.id.timerText)
        gestureIndicator = findViewById(R.id.gestureIndicator)
        settingsText = findViewById(R.id.settingsText)
        countdownText = findViewById(R.id.countdownText)
        recordBtn = findViewById(R.id.recordBtn)
        pauseBtn = findViewById(R.id.pauseBtn)
        playTextBtn = findViewById(R.id.playTextBtn)
        flipBtn = findViewById(R.id.flipBtn)

        useFront = prefs.frontCamera
        limitSec = prefs.limitSeconds
        analysisExecutor = Executors.newSingleThreadExecutor()

        setupPrompter()
        setupButtons()
        updateTimer(0)
        updateUi()

        if (prefs.gestureEnabled) {
            detector = try {
                OpenPalmDetector(
                    this,
                    onOpenPalm = { onOpenPalmGesture() },
                    onHandVisible = { visible -> gestureIndicator.alpha = if (visible) 1f else 0.35f },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Gesto indisponível", e)
                toast("Reconhecimento de gesto indisponível neste aparelho.")
                null
            }
        }
        gestureIndicator.visibility = if (detector != null) View.VISIBLE else View.GONE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            toast("Permissão de câmera não concedida.")
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Se o app for para o segundo plano, finaliza e salva o vídeo
        if (recording != null) stopRecording()
        stopScroll()
        handler.removeCallbacksAndMessages(null)
        countdownText.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScroll()
        detector?.close()
        analysisExecutor.shutdown()
    }

    // ---------- Teleprompter ----------
    private fun setupPrompter() {
        val text = prefs.script.ifBlank { "Cole o seu texto na tela inicial do app." }
        prompterText.text = text
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        prompterText.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        val alpha = (prefs.opacity / 100f * 255).toInt()
        scrollView.setBackgroundColor(Color.argb(alpha, 0, 0, 0))

        // Espaço antes/depois do texto para a primeira e a última linha
        // passarem pela linha-guia (a 25% da altura, perto da câmera frontal).
        scrollView.post {
            val h = scrollView.height
            val guideY = (h * 0.25f).toInt()
            prompterText.setPadding(
                prompterText.paddingLeft, guideY, prompterText.paddingRight, h - guideY
            )
            guideLine.translationY = guideY.toFloat()
        }
        updateSettingsText()
    }

    private fun updateSettingsText() {
        settingsText.text = "A ${prefs.fontSizeSp} · Vel ${prefs.speed}"
    }

    private fun setupButtons() {
        recordBtn.setOnClickListener { onRecordClicked() }
        pauseBtn.setOnClickListener { togglePause() }
        flipBtn.setOnClickListener {
            if (recording == null && !isFinalizing) {
                useFront = !useFront
                bindUseCases()
            }
        }
        playTextBtn.setOnClickListener {
            // Rolar o texto sem gravar (para ensaiar) ou pausar só o texto
            if (scrolling) stopScroll() else startScroll()
        }
        findViewById<Button>(R.id.rewindBtn).setOnClickListener {
            scrollView.smoothScrollTo(0, 0)
        }
        findViewById<Button>(R.id.fontMinus).setOnClickListener { changeFont(-2) }
        findViewById<Button>(R.id.fontPlus).setOnClickListener { changeFont(+2) }
        findViewById<Button>(R.id.speedMinus).setOnClickListener { changeSpeed(-1) }
        findViewById<Button>(R.id.speedPlus).setOnClickListener { changeSpeed(+1) }
    }

    private fun changeFont(delta: Int) {
        prefs.fontSizeSp = prefs.fontSizeSp + delta
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        updateSettingsText()
    }

    private fun changeSpeed(delta: Int) {
        prefs.speed = prefs.speed + delta
        updateSettingsText()
    }

    // ---------- Câmera ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir a câmera", e)
                toast("Não foi possível abrir a câmera.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        // A tela pode ter sido recriada ao girar para paisagem
        if (isFinishing || isDestroyed) return
        val selector = if (useFront && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        val vc = VideoCapture.withOutput(recorder)
        videoCapture = vc

        val det = detector
        val analysis = if (det != null) {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { image -> det.analyze(image) } }
        } else null

        provider.unbindAll()
        try {
            if (analysis != null) {
                provider.bindToLifecycle(this, selector, preview, vc, analysis)
            } else {
                provider.bindToLifecycle(this, selector, preview, vc)
            }
        } catch (e: Exception) {
            // Alguns aparelhos não aguentam gravar + analisar ao mesmo tempo
            Log.w(TAG, "Combinação com análise não suportada; seguindo sem gesto", e)
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, vc)
                if (analysis != null) {
                    toast("Este aparelho não permite gesto e gravação juntos. Use o botão Pausar.")
                    gestureIndicator.visibility = View.GONE
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Falha ao vincular câmera", e2)
                toast("Não foi possível iniciar a câmera.")
            }
        }
    }

    // ---------- Gravação ----------
    private fun onRecordClicked() {
        if (isFinalizing) return
        if (recording != null) {
            stopRecording()
        } else if (countdownText.visibility == View.VISIBLE) {
            // Toque durante a contagem cancela
            handler.removeCallbacksAndMessages(null)
            countdownText.visibility = View.GONE
        } else if (prefs.countdown) {
            runCountdown(3)
        } else {
            startRecording()
        }
    }

    private fun runCountdown(n: Int) {
        if (n == 0) {
            countdownText.visibility = View.GONE
            startRecording()
            return
        }
        countdownText.text = n.toString()
        countdownText.visibility = View.VISIBLE
        handler.postDelayed({ runCountdown(n - 1) }, 1000)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = "Teleprompter_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Teleprompter")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        isPaused = false
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            onRecordEvent(event)
        }
        updateUi()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                startScroll()
                updateUi()
            }
            is VideoRecordEvent.Status -> {
                val sec = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                updateTimer(sec)
                if (limitSec > 0 && sec >= limitSec && recording != null) {
                    stopRecording()
                }
            }
            is VideoRecordEvent.Finalize -> {
                isFinalizing = false
                recording = null
                isPaused = false
                stopScroll()
                val uri = event.outputResults.outputUri
                when {
                    event.error == VideoRecordEvent.Finalize.ERROR_NONE ->
                        toast("Vídeo salvo na galeria (Movies/Teleprompter).")
                    uri != android.net.Uri.EMPTY ->
                        toast("Vídeo salvo, com aviso (código ${event.error}).")
                    else ->
                        toast("Erro ao salvar o vídeo (código ${event.error}).")
                }
                updateUi()
            }
            else -> Unit
        }
    }

    private fun stopRecording() {
        val r = recording ?: return
        recording = null
        isFinalizing = true
        r.stop()
        stopScroll()
        updateUi()
    }

    private fun togglePause() {
        val r = recording ?: return
        if (isPaused) {
            r.resume()
            isPaused = false
            startScroll()
        } else {
            r.pause()
            isPaused = true
            stopScroll()
        }
        updateUi()
    }

    private fun onOpenPalmGesture() {
        if (recording != null) {
            togglePause()
            toast(if (isPaused) "✋ Pausado" else "✋ Gravando")
        }
    }

    // ---------- Interface ----------
    private fun updateTimer(sec: Int) {
        val elapsed = fmt(sec)
        val base = if (limitSec > 0) "$elapsed / ${fmt(limitSec)}" else elapsed
        timerText.text = when {
            recording != null && isPaused -> "⏸ $base"
            recording != null -> "● $base"
            else -> base
        }
        timerText.setTextColor(
            if (recording != null && !isPaused) Color.rgb(255, 82, 82) else Color.WHITE
        )
    }

    private fun updateUi() {
        val active = recording != null
        recordBtn.text = if (active) "■" else "REC"
        recordBtn.textSize = if (active) 26f else 16f
        recordBtn.isEnabled = !isFinalizing
        pauseBtn.isEnabled = active
        pauseBtn.alpha = if (active) 1f else 0.4f
        pauseBtn.text = if (isPaused) "▶ Retomar" else "⏸ Pausar"
        flipBtn.isEnabled = !active && !isFinalizing
        flipBtn.alpha = if (flipBtn.isEnabled) 1f else 0.4f
        if (!active) updateTimer(0)
    }

    private fun fmt(totalSec: Int) = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "RecordActivity"
    }
}
