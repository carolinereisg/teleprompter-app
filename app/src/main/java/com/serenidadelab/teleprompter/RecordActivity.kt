package com.serenidadelab.teleprompter

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import java.io.File
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

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
    private var cameraChoices: List<Pair<String, CameraSelector?>> = emptyList()
    private var camIndex = -1
    private var limitSec = 0
    private var landscape = false
    private var adjustMode = false
    private var resolutionLabel = ""
    /** Gravação atual no modo Edição com Claude (para antes de 400 MB). */
    private var sizeLimited = false
    /** 0 = guias desligadas, 1 = guias suaves, 2 = modo enquadrar. */
    private var guideState = 0

    private var detector: OpenPalmDetector? = null
    private lateinit var analysisExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    // ---------- Rolagem automática ----------
    private var scrolling = false
    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f
    private var wordCount = 1
    private var pxPerWord = 0f

    // ---------- Correções e ritmo adaptativo ----------
    /** Pixels de correção ainda a aplicar (positivo = avançar, negativo = voltar). */
    private var pendingPx = 0f
    /** Segundos em que o texto esteve rolando. */
    private var scrollClock = 0f
    private var wheelAccum = 0f
    private var episodePx = 0f
    private var lastEpisodeClock = 0f
    private var anchorClock = 0f
    private var streakSign = 0f
    private var streakCount = 0
    private var streakPx = 0f
    private val endEpisode = Runnable { finishEpisode() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scrolling) return
            if (lastFrameNanos != 0L) {
                val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                scrollClock += dt
                scrollRemainder += pixelsPerSecond() * dt
                // Correções da rodinha/controle: aplicadas aos poucos (acelera ou volta temporariamente)
                if (pendingPx != 0f) {
                    val step = pendingPx * min(1f, dt * 6f)
                    pendingPx -= step
                    if (abs(pendingPx) < 0.5f) pendingPx = 0f
                    scrollRemainder += step
                }
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
        if (::panelPlay.isInitialized) panelPlay.text = "⏸ Parar rolagem"
    }

    private fun stopScroll() {
        scrolling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        playTextBtn.text = "▶ Texto"
        if (::panelPlay.isInitialized) panelPlay.text = "▶ Testar rolagem"
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
        guideState = if (prefs.showGuides || prefs.showSilhouette) 1 else 0

        useFront = prefs.frontCamera
        limitSec = prefs.limitSeconds
        analysisExecutor = Executors.newSingleThreadExecutor()

        if (landscape) compactBarsForLandscape()
        setupPrompter()
        setupButtons()
        setupTextPanel()
        frameBtn.text = if (guideState == 1) "▦" else "▢"
        frameBtn.alpha = if (guideState == 0) 0.6f else 1f
        val topBar = findViewById<View>(R.id.topBar)
        val sizeRow = findViewById<View>(R.id.sizeRow)
        topBar.post {
            (sizeRow.layoutParams as ViewGroup.MarginLayoutParams).topMargin = topBar.bottom
            sizeRow.requestLayout()
            val pill = findViewById<View>(R.id.statusPill)
            (pill.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                topBar.bottom + (34 * resources.displayMetrics.density).toInt()
            pill.requestLayout()
        }
        textPanel.visibility = View.VISIBLE // ajuste com a câmera ligada logo ao entrar
        hidePointer(window.decorView)
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
        if (isActive) stopRecording()
        if (usbMode) {
            usbCamera?.closeCamera()
            usbCamera = null
            usbRequested = false
        }
        stopScroll()
        handler.removeCallbacksAndMessages(null)
        countdownText.visibility = View.GONE
    }

    override fun onRestart() {
        super.onRestart()
        // Volta do segundo plano: reabre a câmera USB
        if (usbMode) connectUsbCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            usbCamera?.closeCamera()
            usbClient?.unRegister()
            usbClient?.destroy()
        } catch (_: Exception) {
        }
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
        script = prefs.loadScript()
        renderText()
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        prompterText.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        applyOpacity()

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

    private var script: CharSequence = ""

    /** Aplica negrito/cores ao roteiro e mostra no teleprompter. */
    private fun renderText() {
        val text: CharSequence = if (script.isBlank()) "Cole o seu texto na tela inicial do app." else
            ScriptFormat.forDarkBackground(script, prefs.keepColors, prefs.boldAll)
        wordCount = ScriptFormat.wordCount(text).coerceAtLeast(1)
        prompterText.text = text
        recomputePace()
    }

    private fun applyOpacity() {
        val alpha = (prefs.opacity / 100f * 255).toInt()
        scrollView.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    // ---------- Painel Aa: ajustes do texto com a câmera ligada ----------
    private lateinit var textPanel: View
    private lateinit var panelFont: SeekBar
    private lateinit var panelSpeed: SeekBar
    private lateinit var panelOpacity: SeekBar
    private lateinit var panelFontLabel: TextView
    private lateinit var panelSpeedLabel: TextView
    private lateinit var panelOpacityLabel: TextView
    private lateinit var panelPlay: Button

    private fun SeekBar.onChange(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) block(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupTextPanel() {
        textPanel = findViewById(R.id.textPanel)
        panelFont = findViewById(R.id.panelFont)
        panelSpeed = findViewById(R.id.panelSpeed)
        panelOpacity = findViewById(R.id.panelOpacity)
        panelFontLabel = findViewById(R.id.panelFontLabel)
        panelSpeedLabel = findViewById(R.id.panelSpeedLabel)
        panelOpacityLabel = findViewById(R.id.panelOpacityLabel)
        panelPlay = findViewById(R.id.panelPlay)
        val bold = findViewById<SwitchCompat>(R.id.panelBold)
        val colors = findViewById<SwitchCompat>(R.id.panelColors)

        syncPanel()
        bold.isChecked = prefs.boldAll
        colors.isChecked = prefs.keepColors

        panelFont.onChange { p ->
            prefs.fontSizeSp = 16 + p * 2
            prompterText.textSize = prefs.fontSizeSp.toFloat()
            recomputePace()
            syncPanel()
            updateSettingsText()
        }
        panelSpeed.onChange { p ->
            prefs.wpm = 60 + p * 5
            syncPanel()
            updateSettingsText()
        }
        panelOpacity.onChange { p ->
            prefs.opacity = p * 5
            applyOpacity()
            syncPanel()
        }
        bold.setOnCheckedChangeListener { _, checked -> prefs.boldAll = checked; renderText() }
        colors.setOnCheckedChangeListener { _, checked -> prefs.keepColors = checked; renderText() }
        panelPlay.setOnClickListener { if (scrolling) stopScroll() else startScroll() }
        findViewById<Button>(R.id.panelClose).setOnClickListener { textPanel.visibility = View.GONE }
        findViewById<Button>(R.id.textBtn).setOnClickListener {
            if (adjustMode) toggleAdjustMode()
            textPanel.visibility = if (textPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    /** Atualiza barras e rótulos do painel a partir dos valores atuais. */
    private fun syncPanel() {
        if (!::panelFont.isInitialized) return
        panelFont.progress = (prefs.fontSizeSp - 16) / 2
        panelSpeed.progress = (prefs.wpm - 60) / 5
        panelOpacity.progress = prefs.opacity / 5
        panelFontLabel.text = "Tamanho da letra: ${prefs.fontSizeSp}"
        panelSpeedLabel.text = "Velocidade: ${prefs.wpm} palavras por minuto"
        panelOpacityLabel.text = "Escurecimento do fundo: ${prefs.opacity}%"
    }

    private fun updateSettingsText() {
        val cam = cameraChoices.getOrNull(camIndex)?.first ?: ""
        val res = listOf(resolutionLabel, cam).filter { it.isNotEmpty() }.joinToString(" · ")
            .let { if (it.isNotEmpty()) "\n$it" else "" }
        settingsText.text = "A ${prefs.fontSizeSp} · ${prefs.wpm} ppm$res"
    }

    private fun setupButtons() {
        recordBtn.setOnClickListener { onRecordClicked() }
        pauseBtn.setOnClickListener { togglePause() }
        flipBtn.setOnClickListener {
            if (!isActive && !isFinalizing) {
                cameraProvider?.let { buildCameraChoices(it) }
                if (cameraChoices.isNotEmpty()) {
                    camIndex = (camIndex + 1) % cameraChoices.size
                    bindUseCases()
                    toast("Câmera: ${cameraChoices[camIndex].first}")
                }
            }
        }
        frameBtn.setOnClickListener { cycleGuides() }
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

    /** Botão ▦: guias suaves → modo enquadrar → sem guias. Funciona também durante a gravação. */
    private fun cycleGuides() {
        guideState = (guideState + 1) % 3
        applyGuideState()
        toast(
            when (guideState) {
                1 -> "Guias suaves"
                2 -> "Modo enquadrar"
                else -> "Guias desligadas"
            }
        )
    }

    private fun applyGuideState() {
        if ((guideState == 2) != adjustMode) toggleAdjustMode()
        overlay.showGuides = guideState >= 1
        overlay.showSilhouette = guideState >= 1
        frameBtn.text = when (guideState) {
            1 -> "▦"
            2 -> "✓"
            else -> "▢"
        }
        frameBtn.alpha = if (guideState == 0) 0.6f else 1f
    }

    /** Modo ajuste: esconde o texto e destaca silhueta e área de corte. */
    private fun toggleAdjustMode() {
        adjustMode = !adjustMode
        overlay.adjustMode = adjustMode
        val vis = if (adjustMode) View.INVISIBLE else View.VISIBLE
        scrollView.visibility = vis
        guideLine.visibility = vis
        if (adjustMode) textPanel.visibility = View.GONE
    }

    private fun changeFont(delta: Int) {
        prefs.fontSizeSp = prefs.fontSizeSp + delta
        prompterText.textSize = prefs.fontSizeSp.toFloat()
        recomputePace()
        updateSettingsText()
        syncPanel()
    }

    private fun changeSpeed(delta: Int) {
        prefs.wpm = prefs.wpm + delta
        updateSettingsText()
        syncPanel()
    }

    /** Empurra o texto ~2 linhas para frente (+1) ou para trás (−1). */
    private fun nudge(direction: Int) {
        correct(prompterText.lineHeight * 2f * direction)
    }

    /** Aplica uma correção de posição e registra para o ritmo adaptativo. */
    private fun correct(px: Float) {
        val cap = prompterText.lineHeight * 12f
        if (scrolling) {
            pendingPx = (pendingPx + px).coerceIn(-cap, cap)
        } else {
            scrollView.smoothScrollBy(0, px.roundToInt())
        }
        episodePx += px
        handler.removeCallbacks(endEpisode)
        handler.postDelayed(endEpisode, 1200)
    }

    /**
     * Uma "correção" = sequência de giros/toques com pausa de até 1,2 s.
     * Depois de 3 correções seguidas no mesmo sentido, ajusta a velocidade base
     * para a média do que foi corrigido.
     */
    private fun finishEpisode() {
        val net = episodePx
        episodePx = 0f
        val minPx = prompterText.lineHeight * 0.5f
        if (!prefs.adaptivePace || !scrolling || abs(net) < minPx || pxPerWord <= 0f) {
            lastEpisodeClock = scrollClock
            return
        }
        val sg = sign(net)
        if (sg != streakSign) {
            streakSign = sg
            streakCount = 0
            streakPx = 0f
            anchorClock = lastEpisodeClock
        }
        streakCount++
        streakPx += net
        lastEpisodeClock = scrollClock

        if (streakCount >= 3) {
            val span = scrollClock - anchorClock
            if (span >= 8f) {
                val pxPerSec = streakPx / span
                val deltaWpm = (pxPerSec / pxPerWord * 60f * 0.8f)
                val rounded = ((deltaWpm / 5f).roundToInt() * 5).coerceIn(-30, 30)
                if (rounded != 0) {
                    prefs.wpm = prefs.wpm + rounded
                    updateSettingsText()
                    syncPanel()
                    toast("Ritmo ajustado: ${prefs.wpm} ppm")
                }
            }
            streakCount = 0
            streakPx = 0f
            anchorClock = scrollClock
        }
    }

    /** Clique esquerdo: segura / solta o texto (a gravação continua). */
    private fun toggleHold() {
        if (scrolling) stopScroll() else startScroll()
        toast(if (scrolling) "Texto rolando" else "Texto parado")
    }

    // ---------- Mouse Bluetooth ----------
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    // rodinha para cima (valor positivo) = voltar; para baixo = acelerar/avançar
                    wheelAccum += ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val notches = wheelAccum.toInt()
                    if (notches != 0) {
                        wheelAccum -= notches
                        correct(-notches * prompterText.lineHeight * 0.7f)
                    }
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Cliques do mouse não acionam os botões da tela (evita parar a gravação sem querer)
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                (ev.buttonState == 0 || ev.isButtonPressed(MotionEvent.BUTTON_PRIMARY))
            ) {
                toggleHold()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Botão direito / laterais do mouse geram "Voltar": não deixa sair da gravação
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD) &&
            event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    /** Esconde a setinha do mouse na tela de gravação. */
    private fun hidePointer(v: View) {
        v.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        if (v is ViewGroup) for (i in 0 until v.childCount) hidePointer(v.getChildAt(i))
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
                    if (isActive) togglePause() else if (scrolling) stopScroll() else startScroll()
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
        updateSettingsText()
        if (selector == null) {
            enterUsbMode()
            return
        }
        if (usbMode) exitUsbMode()

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
        val list = mutableListOf<Pair<String, CameraSelector?>>()
        // Câmera USB pelo driver próprio do app (funciona mesmo se o Android não a reconhece)
        if (findUvcDevice() != null) list += "USB" to null
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
                    list += (if (n == 1) "USB (sistema)" else "USB (sistema) $n") to sel
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

    // ---------- Câmera USB (UVC): Insta360 Link 2 e outras webcams ----------
    private var usbMode = false
    private var usbClient: MultiCameraClient? = null
    private var usbCamera: CameraUVC? = null
    private var usbRequested = false
    /** Sessão de gravação USB ativa (inclui quando está pausada). */
    private var usbSession = false
    private var usbSegment = 0
    private var usbSegmentStartMs = 0L
    private var usbAccumMs = 0L
    private var usbBaseName = ""
    private var usbCurrentFile: File? = null

    private val isActive: Boolean
        get() = recording != null || usbSession

    private fun isUvc(d: UsbDevice): Boolean {
        if (d.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until d.interfaceCount) {
            if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun findUvcDevice(): UsbDevice? =
        getSystemService(UsbManager::class.java)?.deviceList?.values?.firstOrNull { isUvc(it) }

    private fun enterUsbMode() {
        usbMode = true
        cameraProvider?.unbindAll()
        previewView.visibility = View.INVISIBLE
        findViewById<View>(R.id.usbContainer).visibility = View.VISIBLE
        // A câmera USB entrega vídeo horizontal 16:9
        overlay.landscape = true
        gestureIndicator.visibility = View.GONE
        resolutionLabel = ""
        updateSettingsText()
        if (usbClient == null) {
            usbClient = MultiCameraClient(this, usbCallback).also { it.register() }
        }
        connectUsbCamera()
    }

    private fun exitUsbMode() {
        if (usbSession) usbStopRecording()
        usbCamera?.closeCamera()
        usbCamera = null
        usbRequested = false
        usbMode = false
        findViewById<View>(R.id.usbContainer).visibility = View.GONE
        previewView.visibility = View.VISIBLE
        overlay.landscape = landscape
        gestureIndicator.visibility = if (detector != null) View.VISIBLE else View.GONE
    }

    /** Pede permissão de USB (o Android mostra um aviso na 1ª vez) e abre a câmera. */
    private fun connectUsbCamera() {
        val dev = findUvcDevice()
        if (dev == null) {
            toast("Câmera USB não encontrada. Confira o cabo e se ela está ligada.")
            return
        }
        if (usbRequested) return
        usbRequested = true
        toast("Conectando à câmera USB…")
        usbClient?.requestPermission(dev)
    }

    private val usbCallback = object : IDeviceConnectCallBack {
        override fun onAttachDev(device: UsbDevice?) {
            if (device != null && usbMode && isUvc(device) && usbCamera == null && !usbRequested) {
                runOnUiThread { connectUsbCamera() }
            }
        }

        override fun onDetachDec(device: UsbDevice?) {
            if (device != null && device.deviceId == usbCamera?.device?.deviceId) {
                runOnUiThread {
                    if (usbSession) usbStopRecording()
                    usbCamera?.closeCamera()
                    usbCamera = null
                    usbRequested = false
                    toast("Câmera USB desconectada.")
                }
            }
        }

        override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (device == null || ctrlBlock == null) return
            runOnUiThread { openUsbCamera(device, ctrlBlock) }
        }

        override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            runOnUiThread {
                usbCamera?.closeCamera()
                usbCamera = null
                usbRequested = false
            }
        }

        override fun onCancelDev(device: UsbDevice?) {
            runOnUiThread {
                usbRequested = false
                toast("Permissão da câmera USB negada. Toque em ⟲ para tentar de novo.")
            }
        }
    }

    private fun openUsbCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        if (!usbMode || isDestroyed) return
        usbCamera?.closeCamera()
        val cam = CameraUVC(this, device)
        cam.setUsbControlBlock(ctrlBlock)
        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                runOnUiThread {
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> {
                            val req = self.getCameraRequest()
                            val h = req?.previewHeight ?: 0
                            resolutionLabel = when {
                                h >= 2160 -> "4K"
                                h >= 1080 -> "Full HD"
                                h > 0 -> "${req?.previewWidth}×$h"
                                else -> ""
                            }
                            updateSettingsText()
                            toast("Câmera USB conectada (${device.productName ?: "UVC"}).")
                        }
                        ICameraStateCallBack.State.ERROR -> {
                            usbRequested = false
                            toast("Erro na câmera USB: ${msg ?: "desconhecido"}")
                        }
                        else -> Unit
                    }
                }
            }
        })
        val request = CameraRequest.Builder()
            .setPreviewWidth(if (prefs.uhd) 3840 else 1920)
            .setPreviewHeight(if (prefs.uhd) 2160 else 1080)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setAspectRatioShow(true)
            .setAudioSource(
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) CameraRequest.AudioSource.SOURCE_AUTO else CameraRequest.AudioSource.NONE
            )
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setRawPreviewData(false)
            .setCaptureRawImage(false)
            .create()
        usbCamera = cam
        cam.openCamera(findViewById<AspectRatioTextureView>(R.id.usbView), request)
    }

    private fun usbStartRecording() {
        val cam = usbCamera
        if (cam == null || !cam.isCameraOpened()) {
            toast("A câmera USB ainda não está pronta.")
            return
        }
        usbSession = true
        isPaused = false
        usbSegment = 0
        usbAccumMs = 0L
        usbBaseName = "Teleprompter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sizeLimited = prefs.claudeMode
        findViewById<View>(R.id.sizeRow).visibility = if (sizeLimited) View.VISIBLE else View.GONE
        updateSizeBar(0L, 0f)
        usbStartSegment()
        startScroll()
        updateUi()
        handler.post(usbTicker)
    }

    /** Cada trecho entre pausas vira um arquivo (a câmera USB não pausa dentro do mesmo arquivo). */
    private fun usbStartSegment() {
        val cam = usbCamera ?: return
        usbSegment++
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val name = if (usbSegment == 1) usbBaseName else "${usbBaseName}_parte$usbSegment"
        val base = File(dir, name)
        usbCurrentFile = File(base.absolutePath + ".mp4")
        usbSegmentStartMs = System.currentTimeMillis()
        cam.captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {}
            override fun onError(error: String?) {
                runOnUiThread { toast("Erro ao gravar na câmera USB: ${error ?: ""}") }
            }
            override fun onComplete(path: String?) {
                val f = path?.let { File(it) } ?: return
                Thread { publishToGallery(f) }.start()
            }
        }, base.absolutePath, 0L)
    }

    private fun usbStopSegment() {
        usbAccumMs += System.currentTimeMillis() - usbSegmentStartMs
        usbCamera?.captureVideoStop()
    }

    private fun usbStopRecording() {
        if (!usbSession) return
        if (!isPaused) usbStopSegment()
        usbSession = false
        isPaused = false
        handler.removeCallbacks(usbTicker)
        stopScroll()
        findViewById<View>(R.id.sizeRow).visibility = View.GONE
        hideSizeCountdown()
        updateUi()
    }

    private fun usbTogglePause() {
        if (!usbSession) return
        if (isPaused) {
            isPaused = false
            usbStartSegment()
            startScroll()
        } else {
            isPaused = true
            usbStopSegment()
            stopScroll()
        }
        updateUi()
    }

    private val usbTicker = object : Runnable {
        override fun run() {
            if (!usbSession) return
            val elapsedMs = usbAccumMs + if (isPaused) 0L else System.currentTimeMillis() - usbSegmentStartMs
            val sec = (elapsedMs / 1000).toInt()
            updateTimer(sec)
            if (limitSec > 0 && sec >= limitSec) {
                usbStopRecording()
                return
            }
            if (sizeLimited && !isPaused) {
                val bytes = usbCurrentFile?.length() ?: 0L
                val segSecs = (System.currentTimeMillis() - usbSegmentStartMs) / 1000f
                updateSizeBar(bytes, segSecs)
                if (bytes >= SOFT_LIMIT_BYTES) {
                    usbStopRecording()
                    return
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Copia o vídeo gravado para a galeria (Movies/Teleprompter) e apaga a cópia temporária. */
    private fun publishToGallery(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Teleprompter")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            file.delete()
            runOnUiThread { toast("Vídeo salvo na galeria (Movies/Teleprompter).") }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar vídeo USB", e)
            runOnUiThread { toast("Não foi possível salvar o vídeo: ${e.message}") }
        }
    }

    // ---------- Gravação ----------
    private fun onRecordClicked() {
        if (isFinalizing) return
        if (adjustMode) {
            guideState = 1
            applyGuideState()
        }
        textPanel.visibility = View.GONE
        if (isActive) {
            stopRecording()
        } else if (countdownText.visibility == View.VISIBLE) {
            // Toque durante a contagem cancela
            handler.removeCallbacksAndMessages(null)
            countdownText.visibility = View.GONE
        } else {
            askRecordingMode()
        }
    }

    /** Pergunta o modo antes de cada gravação. */
    private fun askRecordingMode() {
        val options = arrayOf(
            "🎬  Normal (sem limite de tamanho)",
            "✂️  Edição com Claude (para antes de 390 MB)",
        )
        AlertDialog.Builder(this)
            .setTitle("Modo de gravação")
            .setSingleChoiceItems(options, if (prefs.claudeMode) 1 else 0) { dialog, which ->
                prefs.claudeMode = which == 1
                dialog.dismiss()
                if (prefs.countdown) runCountdown(3) else startRecording()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        if (usbMode) {
            usbStartRecording()
            return
        }
        val vc = videoCapture ?: return
        val name = "Teleprompter_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Teleprompter")
        }
        sizeLimited = prefs.claudeMode
        val builder = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values)
        // Trava de segurança: o arquivo nunca passa de 395 MB
        if (sizeLimited) builder.setFileSizeLimit(HARD_LIMIT_BYTES)
        val output = builder.build()
        findViewById<View>(R.id.sizeRow).visibility = if (sizeLimited) View.VISIBLE else View.GONE
        updateSizeBar(0L, 0f)

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
                if (sizeLimited && recording != null) {
                    val bytes = event.recordingStats.numBytesRecorded
                    val secs = event.recordingStats.recordedDurationNanos / 1_000_000_000f
                    updateSizeBar(bytes, secs)
                    if (bytes >= SOFT_LIMIT_BYTES) stopRecording()
                }
            }
            is VideoRecordEvent.Finalize -> {
                isFinalizing = false
                recording = null
                isPaused = false
                stopScroll()
                val uri = event.outputResults.outputUri
                findViewById<View>(R.id.sizeRow).visibility = View.GONE
                hideSizeCountdown()
                when {
                    event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                        toast("Limite de 395 MB atingido. Vídeo salvo.")
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
        if (usbMode) {
            usbStopRecording()
            return
        }
        val r = recording ?: return
        recording = null
        isFinalizing = true
        r.stop()
        stopScroll()
        updateUi()
    }

    private fun togglePause() {
        if (usbMode) {
            usbTogglePause()
            return
        }
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
        if (isActive) {
            togglePause()
            toast(if (isPaused) "✋ Pausado" else "✋ Gravando")
        }
    }

    // ---------- Modo Edição com Claude: barra de tamanho e contagem final ----------
    private fun updateSizeBar(bytes: Long, secs: Float) {
        val bar = findViewById<ProgressBar>(R.id.sizeBar)
        val label = findViewById<TextView>(R.id.sizeText)
        bar.progress = (bytes * 1000 / SOFT_LIMIT_BYTES).toInt().coerceIn(0, 1000)
        val mb = bytes / 1_000_000
        val limitMb = SOFT_LIMIT_BYTES / 1_000_000
        if (secs < 2f || bytes <= 0L) {
            label.text = "$mb / $limitMb MB"
            return
        }
        val rate = bytes / secs // bytes por segundo
        val remaining = ((SOFT_LIMIT_BYTES - bytes) / rate).toInt().coerceAtLeast(0)
        label.text = "$mb / $limitMb MB · resta ${fmt(remaining)}"
        if (remaining <= 10 && !isPaused) {
            // Contagem regressiva até parar
            countdownText.textSize = 72f
            countdownText.setTextColor(Color.rgb(255, 120, 120))
            countdownText.text = (remaining + 1).toString()
            countdownText.visibility = View.VISIBLE
            sizeCountdownShown = true
        } else {
            hideSizeCountdown()
        }
    }

    private var sizeCountdownShown = false

    private fun hideSizeCountdown() {
        if (!sizeCountdownShown) return
        sizeCountdownShown = false
        countdownText.visibility = View.GONE
        countdownText.textSize = 120f
        countdownText.setTextColor(Color.WHITE)
    }

    // ---------- Interface ----------
    private fun updateTimer(sec: Int) {
        val elapsed = fmt(sec)
        val base = if (limitSec > 0) "$elapsed / ${fmt(limitSec)}" else elapsed
        timerText.text = when {
            isActive && isPaused -> "⏸ $base"
            isActive -> "● $base"
            else -> base
        }
        timerText.setTextColor(
            if (isActive && !isPaused) Color.rgb(255, 82, 82) else Color.WHITE
        )
    }

    private var blink: ObjectAnimator? = null

    /** Moldura + selo deixam claro se está gravando, pausado ou parado. */
    private fun updateStatusIndicators() {
        val border = findViewById<View>(R.id.statusBorder)
        val pill = findViewById<TextView>(R.id.statusPill)
        blink?.cancel()
        blink = null
        border.alpha = 1f
        pill.alpha = 1f
        when {
            isActive && isPaused -> {
                border.setBackgroundResource(R.drawable.bg_border_pause)
                border.visibility = View.VISIBLE
                pill.setBackgroundResource(R.drawable.bg_pill_pause)
                pill.setTextColor(Color.BLACK)
                pill.text = "❚❚  PAUSADO"
                pill.visibility = View.VISIBLE
                blink = ObjectAnimator.ofFloat(border, View.ALPHA, 1f, 0.25f).apply {
                    duration = 600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            isActive -> {
                border.setBackgroundResource(R.drawable.bg_border_rec)
                border.visibility = View.VISIBLE
                pill.setBackgroundResource(R.drawable.bg_pill_rec)
                pill.setTextColor(Color.WHITE)
                pill.text = "●  GRAVANDO"
                pill.visibility = View.VISIBLE
            }
            else -> {
                border.visibility = View.GONE
                pill.visibility = View.GONE
            }
        }
    }

    private fun updateUi() {
        updateStatusIndicators()
        val active = isActive
        recordBtn.text = if (active) "■" else "REC"
        recordBtn.textSize = if (active) 26f else 16f
        recordBtn.isEnabled = !isFinalizing
        pauseBtn.isEnabled = active
        pauseBtn.alpha = if (active) 1f else 0.4f
        pauseBtn.text = if (isPaused) "▶ RETOMAR" else "❚❚ Pausar"
        if (isPaused && active) {
            pauseBtn.setBackgroundResource(R.drawable.bg_pill_pause)
            pauseBtn.setTextColor(Color.BLACK)
        } else {
            pauseBtn.setBackgroundResource(R.drawable.bg_round_button)
            pauseBtn.setTextColor(Color.WHITE)
        }
        flipBtn.isEnabled = !active && !isFinalizing
        flipBtn.alpha = if (flipBtn.isEnabled) 1f else 0.4f
        if (!active) updateTimer(0)
    }

    private fun fmt(totalSec: Int) = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "RecordActivity"
        /** Para a gravação aqui (≈ 390 MB). */
        private const val SOFT_LIMIT_BYTES = 390_000_000L
        /** Trava do gravador, caso algo falhe (≈ 395 MB). */
        private const val HARD_LIMIT_BYTES = 395_000_000L
    }
}
