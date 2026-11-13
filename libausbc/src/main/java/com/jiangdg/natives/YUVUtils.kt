package com.jiangdg.natives

/**
 * Substitui a versão nativa (libnative) por Kotlin puro.
 * O app grava pelo caminho OpenGL/Surface, então isto quase nunca é chamado.
 */
object YUVUtils {
    /** NV21 (Y + VU) → NV12/YUV420SP (Y + UV), no próprio array. */
    fun nv21ToYuv420sp(data: ByteArray, width: Int, height: Int) {
        val frame = width * height
        var i = frame
        while (i + 1 < data.size && i + 1 < frame * 3 / 2) {
            val v = data[i]; data[i] = data[i + 1]; data[i + 1] = v
            i += 2
        }
    }

    fun yuv420spToNv21(data: ByteArray, width: Int, height: Int) = nv21ToYuv420sp(data, width, height)

    fun nv21ToYuv420spWithMirror(data: ByteArray, width: Int, height: Int) = nv21ToYuv420sp(data, width, height)

    /** NV21 → I420 (YUV420P), no próprio array. */
    fun nv21ToYuv420p(data: ByteArray, width: Int, height: Int) {
        val frame = width * height
        val q = frame / 4
        if (data.size < frame + 2 * q) return
        val tmp = data.copyOfRange(frame, frame + 2 * q)
        for (k in 0 until q) {
            data[frame + k] = tmp[2 * k + 1]      // U
            data[frame + q + k] = tmp[2 * k]      // V
        }
    }

    fun nv21ToYuv420pWithMirror(data: ByteArray, width: Int, height: Int) = nv21ToYuv420p(data, width, height)

    /** Gira um quadro NV21 em 90/180/270 graus, no próprio array. */
    fun nativeRotateNV21(data: ByteArray, width: Int, height: Int, degree: Int) {
        val frame = width * height
        if (data.size < frame * 3 / 2 || degree % 360 == 0) return
        val src = data.copyOf()
        val d = ((degree % 360) + 360) % 360
        // luminância
        for (y in 0 until height) for (x in 0 until width) {
            val (nx, ny, nw) = when (d) {
                90 -> Triple(height - 1 - y, x, height)
                180 -> Triple(width - 1 - x, height - 1 - y, width)
                else -> Triple(y, width - 1 - x, height)
            }
            data[ny * nw + nx] = src[y * width + x]
        }
        // crominância (pares VU)
        val cw = width / 2
        val ch = height / 2
        for (y in 0 until ch) for (x in 0 until cw) {
            val (nx, ny, nw) = when (d) {
                90 -> Triple(ch - 1 - y, x, ch)
                180 -> Triple(cw - 1 - x, ch - 1 - y, cw)
                else -> Triple(y, cw - 1 - x, ch)
            }
            val s = frame + 2 * (y * cw + x)
            val t = frame + 2 * (ny * nw + nx)
            data[t] = src[s]
            data[t + 1] = src[s + 1]
        }
    }
}
