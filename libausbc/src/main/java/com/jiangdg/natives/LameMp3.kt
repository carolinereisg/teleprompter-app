package com.jiangdg.natives

/** Gravação em MP3 não é usada pelo app; esta versão não faz nada. */
object LameMp3 {
    fun lameInit(inSampleRate: Int, outChannel: Int, outSampleRate: Int, outBitRate: Int, quality: Int) {}
    fun lameEncode(leftBuf: ShortArray?, rightBuf: ShortArray?, sampleRate: Int, mp3Buf: ByteArray?): Int = 0
    fun lameFlush(mp3buf: ByteArray?): Int = 0
    fun lameClose() {}
}
