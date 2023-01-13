package com.dan.videostab

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import org.opencv.android.Utils
import org.opencv.core.Mat

class VideoEncoder(
    private val frameRate: Int,
    width: Int,
    height: Int,
    private val encoder: MediaCodec,
    private val muxer: MediaMuxer,
    private val surface: Surface
) {
    companion object {
        private const val TIMEOUT = 1000L
        private const val MIME_TYPE_H264 = "video/avc"
        private const val MIME_TYPE_H265 = "video/hevc"

        fun create(path: String, frameRate: Int, width: Int, height: Int, h265: Boolean = true): VideoEncoder {
            val mimeType = if (h265) MIME_TYPE_H265 else MIME_TYPE_H264

            val format = MediaFormat.createVideoFormat(mimeType, width, height)

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            return VideoEncoder(frameRate, width, height, encoder, muxer, surface)
        }
    }

    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bitmapRect = Rect(0,0, width, height)
    private var frameIndex = 0
    private var open = true
    private var videoTrackIndex = -1

    val isOpened: Boolean
        get() = open

    private fun drainEncoder(end: Boolean) {
        if (end) encoder.signalEndOfInputStream()

        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outBufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT)
            if (outBufferId >= 0) {
                val encodedBuffer = encoder.getOutputBuffer(outBufferId) ?: break
                bufferInfo.presentationTimeUs = frameIndex * 1000000L / frameRate
                muxer.writeSampleData(videoTrackIndex, encodedBuffer, bufferInfo)
                encoder.releaseOutputBuffer(outBufferId, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else if (MediaCodec.INFO_TRY_AGAIN_LATER == outBufferId) {
                if (!end) break
            } else if (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == outBufferId) {
                videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
            }

        }
    }

    fun release() {
        if (!open) return

        drainEncoder(true)

        open = false
        encoder.stop()
        encoder.release()

        if (videoTrackIndex >= 0) muxer.stop()
        muxer.release()
        surface.release()
    }

    fun write(frame: Mat) {
        if (!open) return

        Utils.matToBitmap(frame, bitmap)

        val canvas = surface.lockHardwareCanvas() ?: return
        canvas.drawBitmap(bitmap, null, bitmapRect, null)
        surface.unlockCanvasAndPost(canvas)

        drainEncoder(false)
        frameIndex += 1
    }
}
