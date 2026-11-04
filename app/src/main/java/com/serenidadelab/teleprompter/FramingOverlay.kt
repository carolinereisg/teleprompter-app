package com.serenidadelab.teleprompter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Desenha por cima da câmera:
 *  - o quadro real do vídeo (16:9 na horizontal, 9:16 na vertical);
 *  - a área do outro corte (9:16 dentro do horizontal, ou 16:9 dentro do vertical);
 *  - faixas aproximadas onde a interface do Reels/TikTok cobre a imagem;
 *  - uma silhueta de cabeça e ombros e a linha dos olhos.
 * Nada disso é gravado no vídeo.
 */
class FramingOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var landscape = false
        set(v) { field = v; invalidate() }
    var showGuides = true
        set(v) { field = v; invalidate() }
    var showSilhouette = true
        set(v) { field = v; invalidate() }
    /** Modo ajuste: escurece o que fica fora do corte e mostra legendas. */
    var adjustMode = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.argb(200, 255, 255, 255)
    }
    private val cropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.rgb(227, 168, 87)
    }
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val uiZonePaint = Paint().apply { color = Color.argb(70, 255, 82, 82) }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(200, 120, 220, 255)
        pathEffect = DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f)
    }
    private val silStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.argb(220, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
    }
    private val silFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(35, 255, 255, 255) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * d; isFakeBoldText = true
        setShadowLayer(3 * d, 0f, 0f, Color.BLACK)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // necessário para as sombras
    }

    /** Retângulo do vídeo gravado dentro da tela (mesma conta do PreviewView em FIT_CENTER). */
    fun frameRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val ratio = if (landscape) 16f / 9f else 9f / 16f
        return if (w / h > ratio) {
            val fw = h * ratio
            RectF((w - fw) / 2, 0f, (w + fw) / 2, h)
        } else {
            val fh = w / ratio
            RectF(0f, (h - fh) / 2, w, (h + fh) / 2)
        }
    }

    /** Área que sobrevive aos dois cortes. */
    private fun cropRect(f: RectF): RectF =
        if (landscape) {
            val cw = f.height() * 9f / 16f
            RectF(f.centerX() - cw / 2, f.top, f.centerX() + cw / 2, f.bottom)
        } else {
            val ch = f.width() * 9f / 16f
            RectF(f.left, f.centerY() - ch / 2, f.right, f.centerY() + ch / 2)
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || (!showGuides && !showSilhouette && !adjustMode)) return
        val f = frameRect()
        val c = cropRect(f)

        if (adjustMode) {
            // escurece o que só aparece em um dos formatos
            canvas.drawRect(f.left, f.top, f.right, c.top, dimPaint)
            canvas.drawRect(f.left, c.bottom, f.right, f.bottom, dimPaint)
            canvas.drawRect(f.left, c.top, c.left, c.bottom, dimPaint)
            canvas.drawRect(c.right, c.top, f.right, c.bottom, dimPaint)
        }

        if (showGuides || adjustMode) {
            // retângulo vertical (onde fica o Reels/TikTok): faixas da interface
            val vertical = if (landscape) c else f
            if (adjustMode) {
                val top = vertical.top + vertical.height() * 0.12f
                val bottom = vertical.bottom - vertical.height() * 0.22f
                canvas.drawRect(vertical.left, vertical.top, vertical.right, top, uiZonePaint)
                canvas.drawRect(vertical.left, bottom, vertical.right, vertical.bottom, uiZonePaint)
                canvas.drawText("interface do app", vertical.left + 6 * d, bottom + 16 * d, labelPaint)
            }
            canvas.drawRect(f, framePaint)
            canvas.drawRect(c, cropPaint)
            if (adjustMode || showGuides) {
                val bigLabel = if (landscape) "16:9 YouTube (quadro inteiro)" else "9:16 Reels/TikTok (quadro inteiro)"
                val smallLabel = if (landscape) "9:16 Reels/TikTok" else "16:9 YouTube"
                canvas.drawText(bigLabel, f.left + 8 * d, f.bottom - 8 * d, labelPaint)
                canvas.drawText(smallLabel, c.left + 6 * d, c.top + 16 * d, labelPaint)
            }
        }

        if (showSilhouette || adjustMode) drawSilhouette(canvas, f, c)
    }

    private fun drawSilhouette(canvas: Canvas, f: RectF, c: RectF) {
        val cx = c.centerX()
        // altura da cabeça e linha dos olhos ajustadas à área comum aos dois cortes
        val headH: Float
        val eyeY: Float
        if (landscape) {
            headH = c.height() * 0.24f
            eyeY = c.top + c.height() * 0.36f
        } else {
            headH = c.height() * 0.36f
            eyeY = c.top + c.height() * 0.40f
        }
        val headW = headH * 0.76f
        val head = RectF(cx - headW / 2, eyeY - headH * 0.52f, cx + headW / 2, eyeY + headH * 0.48f)

        val neckW = headH * 0.36f
        val shoulderY = head.bottom + headH * 0.30f
        val shoulderHalf = (headH * 1.15f).coerceAtMost(c.width() * 0.48f)
        val bottom = c.bottom

        val body = Path().apply {
            moveTo(cx - neckW / 2, head.bottom - headH * 0.08f)
            lineTo(cx - neckW / 2, head.bottom + headH * 0.12f)
            cubicTo(
                cx - neckW, shoulderY - headH * 0.06f,
                cx - shoulderHalf * 0.85f, shoulderY - headH * 0.02f,
                cx - shoulderHalf, shoulderY + headH * 0.35f
            )
            lineTo(cx - shoulderHalf * 1.05f, bottom)
            lineTo(cx + shoulderHalf * 1.05f, bottom)
            lineTo(cx + shoulderHalf, shoulderY + headH * 0.35f)
            cubicTo(
                cx + shoulderHalf * 0.85f, shoulderY - headH * 0.02f,
                cx + neckW, shoulderY - headH * 0.06f,
                cx + neckW / 2, head.bottom + headH * 0.12f
            )
            lineTo(cx + neckW / 2, head.bottom - headH * 0.08f)
        }
        canvas.drawPath(body, silFill)
        canvas.drawOval(head, silFill)
        canvas.drawPath(body, silStroke)
        canvas.drawOval(head, silStroke)

        // linha dos olhos
        canvas.drawLine(f.left, eyeY, f.right, eyeY, eyePaint)
        canvas.drawText("olhos", f.left + 8 * d, eyeY - 6 * d, labelPaint)
    }
}
