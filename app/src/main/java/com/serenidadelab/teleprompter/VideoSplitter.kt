package com.serenidadelab.teleprompter

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Divide um vídeo grande em partes de ~380 MB (sempre abaixo de 398 MB),
 * sem recomprimir: cada parte começa num quadro-chave e toca sozinha.
 * O original não é alterado; as partes vão para Movies/Teleprompter/<nome>_partes.
 */
object VideoSplitter {

    /** Tamanho-alvo de cada parte (bytes de mídia). O contêiner MP4 soma menos de 1%. */
    const val TARGET_BYTES = 380_000_000L
    const val MAX_BYTES = 398_000_000L

    class Result(val parts: Int, val folder: String, val totalMb: Long)

    fun split(context: Context, src: Uri, onProgress: (part: Int, fraction: Float) -> Unit): Result {
        val resolver = context.contentResolver
        val inPfd = resolver.openFileDescriptor(src, "r")
            ?: throw IllegalArgumentException("Não foi possível abrir o vídeo.")
        inPfd.use { pfd ->
            val total = pfd.statSize
            if (total in 1..MAX_BYTES) {
                throw IllegalArgumentException(
                    "Este vídeo tem ${total / 1_000_000} MB: já está abaixo de 398 MB, não precisa dividir."
                )
            }
            val base = (displayName(context, src) ?: "video").substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            val folder = "Movies/Teleprompter/${base}_partes"

            val rotation = MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(pfd.fileDescriptor)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    0
                } finally {
                    r.release()
                }
            }

            val ex = MediaExtractor()
            ex.setDataSource(pfd.fileDescriptor)
            val trackCount = ex.trackCount
            val formats = (0 until trackCount).map { ex.getTrackFormat(it) }
            val selected = ArrayList<Int>()
            var videoTrack = -1
            for (i in 0 until trackCount) {
                val mime = formats[i].getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    selected.add(i)
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                }
            }
            if (videoTrack < 0) {
                ex.release()
                throw IllegalArgumentException("Nenhuma faixa de vídeo encontrada no arquivo.")
            }
            val durationUs = if (formats[videoTrack].containsKey(MediaFormat.KEY_DURATION))
                formats[videoTrack].getLong(MediaFormat.KEY_DURATION) else 0L

            val buf = ByteBuffer.allocate(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val trackMap = IntArray(trackCount) { -1 }

            var part = 0
            var muxer: MediaMuxer? = null
            var outPfd: ParcelFileDescriptor? = null
            var outUri: Uri? = null
            var partBytes = 0L
            var partStartUs = 0L
            var lastReported = -1

            fun openPart(startUs: Long) {
                part++
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, String.format(Locale.US, "%s_parte_%02d.mp4", base, part))
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Não foi possível criar a parte $part.")
                val fd = resolver.openFileDescriptor(uri, "rw")
                    ?: throw IllegalStateException("Não foi possível gravar a parte $part.")
                val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                if (rotation in setOf(0, 90, 180, 270)) m.setOrientationHint(rotation)
                for (i in selected) trackMap[i] = m.addTrack(formats[i])
                m.start()
                muxer = m
                outPfd = fd
                outUri = uri
                partBytes = 0L
                partStartUs = startUs
            }

            fun closePart() {
                try {
                    muxer?.stop()
                } finally {
                    muxer?.release()
                    outPfd?.close()
                    outUri?.let { uri ->
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        resolver.update(uri, done, null, null)
                    }
                    muxer = null
                    outPfd = null
                    outUri = null
                }
            }

            try {
                openPart(0L)
                while (true) {
                    val track = ex.sampleTrackIndex
                    if (track < 0) break
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val time = ex.sampleTime
                    val isKey = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0

                    // Nova parte ao atingir o tamanho-alvo, sempre num quadro-chave de vídeo
                    if (track == videoTrack && isKey && partBytes >= TARGET_BYTES) {
                        closePart()
                        openPart(time)
                    }
                    val pts = time - partStartUs
                    if (pts >= 0) {
                        info.set(0, size, pts, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer?.writeSampleData(trackMap[track], buf, info)
                        partBytes += size
                    }
                    if (durationUs > 0) {
                        val pct = (time * 100 / durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(part, pct / 100f)
                        }
                    }
                    ex.advance()
                }
            } finally {
                closePart()
                ex.release()
            }
            return Result(part, folder, total / 1_000_000)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}
