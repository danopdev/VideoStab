package com.dan.videostab

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc.COLOR_RGB2YUV_YV12
import org.opencv.imgproc.Imgproc.cvtColor


class VideoEncoderInternal(val codec: MediaCodec, val muxer: MediaMuxer, val alignedHeight: Int)


class VideoEncoder(val path: String, val frameRate: Int, val width: Int, val height: Int) {

    companion object {
        const val TIMEOUT = 1000L
        const val MIME_TYPE = "video/avc"
    }

    private var encoder: VideoEncoderInternal? = null

    init {
        prepareEncoder()
    }

    fun isOpened(): Boolean {
        return null != encoder
    }

    private fun prepareEncoder() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        //format.setInteger(MediaFormat.KEY_BIT_RATE, ...)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, frameRate / 2)

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.addTrack(codec.outputFormat)
        muxer.start()

        val alignedHeight = ((height + 15) / 16) * 16

        encoder = VideoEncoderInternal(codec, muxer, alignedHeight)
    }

    fun encode(encoder: VideoEncoderInternal, data: ByteArray?) {
        val codec = encoder.codec

        val inputBufferId = codec.dequeueInputBuffer(TIMEOUT)
        if (inputBufferId >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferId) ?: return
            val startIndex = inputBuffer.position()
            if (null != data && data.isNotEmpty()) {
                inputBuffer.put(data)
                codec.queueInputBuffer(inputBufferId, startIndex, data.size, 0, 0)
            } else {
                codec.queueInputBuffer(inputBufferId, startIndex, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT)
        when {
            outputBufferId >= 0 -> {
                val outputBuffer = codec.getOutputBuffer(outputBufferId)
                if (null != outputBuffer) {
                    encoder.muxer.writeSampleData(0, outputBuffer, bufferInfo)
                }
                codec.releaseOutputBuffer(outputBufferId, 0L)
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == outputBufferId -> {
                // ???
            }
        }
    }

    fun release() {
        val encoder = this.encoder ?: return
        this.encoder = null

        encode( encoder, null )

        encoder.codec.stop()
        encoder.codec.release()
        encoder.muxer.stop()
        encoder.muxer.release()
    }

    fun encode(frame: Mat) {
        val encoder = this.encoder ?: return
        val frame420 = Mat()

        cvtColor( frame, frame420, COLOR_RGB2YUV_YV12 )

        val buffer = ByteArray((frame420.total() * frame.elemSize()).toInt())
        frame420.get(0, 0, buffer)

        encode( encoder, buffer )
    }
}
