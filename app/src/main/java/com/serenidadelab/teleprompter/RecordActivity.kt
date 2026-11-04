package com.serenidadelab.teleprompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
    private lateinit var overlay: FramingOverlay
    private lateinit var timerText: TextView
    private lateinit var gestureIndicator: TextView
    private lateinit var settingsText: TextView
    private lateinit var countdownText: TextView
    private lateinit var recordBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var playTextBtn: Button
    private lateinit var flipBtn: Button
    private lateinit var frameBtn: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isPaused = false
    private var isFinalizing = false
    private var useFront = true
    private var cameraChoices: List<Pair<String, CameraSelector>> = emptyList()
    private var camIndex = -1
    private var limitSec = 0
    private var landscape = false
    private var adjustMode = false
    private var resolutionLabel = ""

    private var detector: OpenPalmDetector? = null
    private lateinit var analysisExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Rolagem automática ----------
    private var scrolling = false
    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f
    private var wordCount = 1
    private var pxPerWord = 0f

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

    /** Palavras por minuto → pixels por segundo, de acordo com o texto como está diagramado. */
    private fun pixelsPerSecond(): Float = prefs.wpm / 60f * pxPerWord

    /** Recalcula quantos pixels de texto correspondem, em média, a uma palavra. */
    private fun recomputePace() {
        prompterText.post {
            val layout = prompterText.layout ?: return@post
            pxPerWord = layout.height.toFloat() / wordCount.coerceAtLeast(1)
        }
    }

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
        landscape = prefs.landscape
        requestedOrientation = if (landscape)
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
        overlay = findViewById(R.id.framingOverlay)
        timerText = findViewById(R.id.timerText)
        gestureIndicator = findViewById(R.id.gestureIndicator)
        settingsText = findViewById(R.id.settingsText)
        countdownText = findViewById(R.id.countdownText)
        recordBtn = findViewById(R.id.recordBtn)
        pauseBtn = findViewById(R.id.pauseBtn)
        playTextBtn = findViewById(R.id.playTextBtn)
        flipBtn = findViewById(R.id.flipBtn)
        frameBtn = findViewById(R.id.frameBtn)

        // Mostra o quadro inteiro que será gravado (sem cortar), para os guias baterem com o vídeo
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        overlay.landscape = landscape
        overlay.showGuides = prefs.showGuides
        overlay.showSilhouette = prefs.showSilhouette

        useFront = prefs.frontCamera
        limitSec = prefs.limitSeconds
        analysisExecutor = Executors.newSingleThreadExecutor()

        if (landscape) compactBarsForLandscape()
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

    /** Na horizontal, coloca os botões lado a lado para sobrar mais tela. */
    private fun compactBarsForLandscape() {
        for (id in intArrayOf(R.id.leftCol, R.id.rightCol)) {
            val col = findViewById<LinearLayout>(id)
            col.orientation = LinearLayout.HORIZONTAL
            for (i in 0 until col.childCount) {
                val lp = col.getChildAt(i).layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = 0
                lp.marginStart = (4 * resources.displayMetrics.density).toInt()
                col.getChildAt(i).layoutParams = lp
            }
        }
        val bar = findViewById<View>(R.id.bottomBar)
        (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
            (8 * resources.displayMetrics.density).toInt()
        (findViewById<View>(R.id.topBar).layoutParams as ViewGroup.MarginLayoutParams).topMargin =
            (8 * resources.displayMetrics.density).toInt()
        recordBtn.layoutParams = recordBtn.layoutParams.apply {
            val s = (60 * resources.displayMetrics.density).toInt()
            width = s; height = s
        }
    }

    // ---------- Teleprompter ----------
    private fun setupPrompter() {
        val script = prefs.loadScript()
        val text: CharSequence = if (script.isBlank()) "Cole o seu texto na tela inicial do app." else
            ScriptFormat.forDarkBackground(script, prefs.keepColors, prefs.boldAll)
        wordCount = ScriptFormat.wordCount(text).coerceAtLeast(1)
        prompterText.text = text
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        prompterText.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        val alpha = (prefs.opacity / 100f * 255).toInt()
        scrollView.setBackgroundColor(Color.argb(alpha, 0, 0, 0))

        // Espaço antes/depois do texto para a primeira e a última linha
        // passarem pela linha-guia (a 25% da altura, perto da câmera frontal).
        scrollView.post {
            val h = scrollView.height
            val w = scrollView.width
            val guideY = (h * 0.25f).toInt()
            // Na horizontal, uma coluna central mais estreita reduz o movimento dos olhos
            val side = if (landscape) (w * 0.16f).toInt() else prompterText.paddingLeft
            prompterText.setPadding(side, guideY, side, h - guideY)
            guideLine.translationY = guideY.toFloat()
            recomputePace()
        }
        updateSettingsText()
    }

    private fun updateSettingsText() {
        val res = if (resolutionLabel.isNotEmpty()) "\n$resolutionLabel" else ""
        settingsText.text = "A ${prefs.fontSizeSp} · ${prefs.wpm} ppm$res"
    }

    private fun setupButtons() {
        recordBtn.setOnClickListener { onRecordClicked() }
        pauseBtn.setOnClickListener { togglePause() }
        flipBtn.setOnClickListener {
            if (recording == null && !isFinalizing) {
                cameraProvider?.let { buildCameraChoices(it) }
                if (cameraChoices.isNotEmpty()) {
                    camIndex = (camIndex + 1) % cameraChoices.size
                    bindUseCases()
                    toast("Câmera: ${cameraChoices[camIndex].first}")
                }
            }
        }
        frameBtn.setOnClickListener { toggleAdjustMode() }
        playTextBtn.setOnClickListener {
            // Rolar o texto sem gravar (para ensaiar) ou pausar só o texto
            if (scrolling) stopScroll() else startScroll()
        }
        findViewById<Button>(R.id.rewindBtn).setOnClickListener { scrollView.smoothScrollTo(0, 0) }
        findViewById<Button>(R.id.fontMinus).setOnClickListener { changeFont(-2) }
        findViewById<Button>(R.id.fontPlus).setOnClickListener { changeFont(+2) }
        findViewById<Button>(R.id.speedMinus).setOnClickListener { changeSpeed(-10) }
        findViewById<Button>(R.id.speedPlus).setOnClickListener { changeSpeed(+10) }
    }

    /** Modo ajuste: esconde o texto e destaca silhueta e área de corte. */
    private fun toggleAdjustMode() {
        adjustMode = !adjustMode
        overlay.adjustMode = adjustMode
        val vis = if (adjustMode) View.INVISIBLE else View.VISIBLE
        scrollView.visibility = vis
        guideLine.visibility = vis
        frameBtn.text = if (adjustMode) "✓ Pronto" else "▦ Enquadrar"
    }

    private fun changeFont(delta: Int) {
        prefs.fontSizeSp = prefs.fontSizeSp + delta
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        recomputePace()
        updateSettingsText()
    }

    private fun changeSpeed(delta: Int) {
        prefs.wpm = prefs.wpm + delta
        updateSettingsText()
    }

    /** Empurra o texto ~2 linhas para frente (+1) ou para trás (−1). */
    private fun nudge(direction: Int) {
        val step = (prompterText.lineHeight * 2 * direction)
        scrollView.smoothScrollBy(0, step)
    }

    // ---------- Controle remoto (volume, passador de slides, Bluetooth) ----------
    private fun isBackKey(k: Int) = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_PAGE_UP ||
        k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_LEFT ||
        k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || k == KeyEvent.KEYCODE_MEDIA_REWIND

    private fun isForwardKey(k: Int) = k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_PAGE_DOWN ||
        k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
        k == KeyEvent.KEYCODE_MEDIA_NEXT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD

    private fun isPlayKey(k: Int) = k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY ||
        k == KeyEvent.KEYCODE_MEDIA_PAUSE || k == KeyEvent.KEYCODE_HEADSETHOOK ||
        k == KeyEvent.KEYCODE_SPACE || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_CAMERA

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            isBackKey(keyCode) -> {
                if (prefs.remoteChangesSpeed) changeSpeed(-10) else nudge(-1)
                return true
            }
            isForwardKey(keyCode) -> {
                if (prefs.remoteChangesSpeed) changeSpeed(+10) else nudge(+1)
                return true
            }
            isPlayKey(keyCode) -> {
                if (event.repeatCount == 0) {
                    if (recording != null) togglePause() else if (scrolling) stopScroll() else startScroll()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isBackKey(keyCode) || isForwardKey(keyCode) || isPlayKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
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
        if (cameraChoices.isEmpty()) buildCameraChoices(provider)
        if (cameraChoices.isEmpty()) {
            toast("Nenhuma câmera encontrada.")
            return
        }
        if (camIndex !in cameraChoices.indices) {
            // Câmera USB conectada tem prioridade; senão, frontal ou traseira conforme o menu
            val usb = cameraChoices.indexOfFirst { it.first.startsWith("USB") }
            camIndex = when {
                usb >= 0 -> usb.also { toast("Câmera USB detectada: usando a câmera externa.") }
                else -> cameraChoices.indexOfFirst { it.first == (if (useFront) "Frontal" else "Traseira") }
                    .coerceAtLeast(0)
            }
        }
        val selector = cameraChoices[camIndex].second
        flipBtn.text = "⟲ " + cameraChoices[camIndex].first

        // Pré-visualização no mesmo formato 16:9 do vídeo
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val target = if (prefs.uhd) Quality.UHD else Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(target, FallbackStrategy.lowerQualityOrHigherThan(target)))
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
        var camera: Camera? = null
        try {
            camera = if (analysis != null) {
                provider.bindToLifecycle(this, selector, preview, vc, analysis)
            } else {
                provider.bindToLifecycle(this, selector, preview, vc)
            }
        } catch (e: Exception) {
            // Alguns aparelhos não aguentam gravar + analisar ao mesmo tempo
            Log.w(TAG, "Combinação com análise não suportada; seguindo sem gesto", e)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, vc)
                if (analysis != null) {
                    toast("Este aparelho não permite gesto e gravação juntos. Use o botão Pausar ou o controle.")
                    gestureIndicator.visibility = View.GONE
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Falha ao vincular câmera", e2)
                toast("Não foi possível iniciar a câmera.")
            }
        }
        showResolution(camera)
    }

    /**
     * Lista as câmeras disponíveis: frontal, traseira e câmeras USB (UVC),
     * como a Insta360 Link 2, quando o Android do aparelho as reconhece.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun buildCameraChoices(provider: ProcessCameraProvider) {
        val list = mutableListOf<Pair<String, CameraSelector>>()
        try {
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) list += "Frontal" to CameraSelector.DEFAULT_FRONT_CAMERA
        } catch (_: Exception) {
        }
        try {
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) list += "Traseira" to CameraSelector.DEFAULT_BACK_CAMERA
        } catch (_: Exception) {
        }
        var n = 0
        for (info in provider.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing == CameraMetadata.LENS_FACING_EXTERNAL) {
                    val id = c2.cameraId
                    val sel = CameraSelector.Builder()
                        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
                        .build()
                    n++
                    list += (if (n == 1) "USB" else "USB $n") to sel
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ler câmera", e)
            }
        }
        cameraChoices = list
    }

    /** Mostra se a câmera escolhida grava em 4K ou Full HD. */
    @Suppress("DEPRECATION")
    private fun showResolution(camera: Camera?) {
        val info = camera?.cameraInfo ?: return
        val supported = try {
            QualitySelector.getSupportedQualities(info)
        } catch (_: Exception) {
            emptyList()
        }
        val has4k = supported.contains(Quality.UHD)
        resolutionLabel = when {
            prefs.uhd && has4k -> "4K"
            supported.contains(Quality.FHD) -> "Full HD"
            supported.isNotEmpty() -> "HD"
            else -> ""
        }
        updateSettingsText()
        if (prefs.uhd && !has4k) toast("4K não disponível nesta câmera; gravando em $resolutionLabel.")
    }

    // ---------- Gravação ----------
    private fun onRecordClicked() {
        if (isFinalizing) return
        if (adjustMode) toggleAdjustMode()
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
        val name = "Teleprompter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event -> onRecordEvent(event) }
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
                if (limitSec > 0 && sec >= limitSec && recording != null) stopRecording()
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
