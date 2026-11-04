package com.serenidadelab.teleprompter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * Detecta o gesto "mão aberta" (Open_Palm) com o MediaPipe Gesture Recognizer.
 * Dispara [onOpenPalm] quando a mão aberta fica visível por ~0,4 s.
 * Para disparar de novo, é preciso abaixar a mão (ou fechar) e mostrar outra vez.
 */
class OpenPalmDetector(
    context: Context,
    private val onOpenPalm: () -> Unit,
    private val onHandVisible: (Boolean) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: GestureRecognizer? = null
    @Volatile private var closed = false

    private var lastFrameMs = 0L
    private var consecutive = 0
    private var armed = true
    private var lastTriggerMs = 0L
    private var lastVisible = false

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.e(TAG, "Erro no reconhecimento de gestos", e) }
            .build()
        recognizer = GestureRecognizer.createFromOptions(context, options)
    }

    /** Chamado pelo ImageAnalysis da CameraX (thread de análise). */
    fun analyze(image: ImageProxy) {
        val r = recognizer
        val now = SystemClock.uptimeMillis()
        // ~10 quadros por segundo bastam para gestos e poupam bateria
        if (r == null || closed || now - lastFrameMs < 100) {
            image.close()
            return
        }
        lastFrameMs = now
        val rotation = image.imageInfo.rotationDegrees
        var converted: Bitmap? = null
        try {
            converted = image.toBitmap()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao converter quadro", e)
        } finally {
            image.close()
        }
        val bitmap = converted ?: return

        val upright = if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } else bitmap

        try {
            r.recognizeAsync(BitmapImageBuilder(upright).build(), now)
        } catch (e: Exception) {
            Log.w(TAG, "recognizeAsync falhou", e)
        }
    }

    private fun handleResult(result: GestureRecognizerResult) {
        val gestures = result.gestures()
        val top = gestures.firstOrNull()?.firstOrNull()
        val handVisible = gestures.isNotEmpty()
        val isOpen = top != null && top.categoryName() == "Open_Palm" && top.score() >= 0.6f

        if (handVisible != lastVisible) {
            lastVisible = handVisible
            mainHandler.post { onHandVisible(handVisible) }
        }

        if (isOpen) {
            consecutive++
        } else {
            consecutive = 0
            armed = true
        }

        val now = SystemClock.uptimeMillis()
        if (armed && consecutive >= 4 && now - lastTriggerMs > 1500) {
            armed = false
            consecutive = 0
            lastTriggerMs = now
            mainHandler.post { onOpenPalm() }
        }
    }

    fun close() {
        closed = true
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    companion object {
        const val MODEL_ASSET = "gesture_recognizer.task"
        private const val TAG = "OpenPalmDetector"
    }
}
