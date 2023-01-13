package com.dan.videostab

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

class VideoMerge {
    companion object {
        private const val BUFFER_SIZE = 1024 * 1024 //1 MB
        private const val VIDEO_MIME_PREFIX = "video/"

        private fun addTracks( muxer: MediaMuxer, sourceFile: String, filter: (String)->Boolean ) {
            val extractor = MediaExtractor()
            extractor.setDataSource(sourceFile)

            for (sourceTrackIndex in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(sourceTrackIndex)
                val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (filter(trackMime)) muxer.addTrack(trackFormat)
            }

            extractor.release()
        }

        private fun copyTracks( muxer: MediaMuxer, sourceFile: String, buffer: ByteBuffer, startDestTrackIndex: Int, filter: (String)->Boolean ): Int {
            val extractor = MediaExtractor()
            val bufferInfo = BufferInfo()
            var destTrackIndex = startDestTrackIndex

            extractor.setDataSource(sourceFile)

            for (sourceTrackIndex in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(sourceTrackIndex)
                val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (!filter(trackMime)) continue

                extractor.selectTrack(sourceTrackIndex)

                while(true) {
                    val readSize = extractor.readSampleData(buffer, 0)
                    if (readSize <= 0) break

                    bufferInfo.set(
                        0,
                        readSize,
                        extractor.sampleTime,
                        if (0 != (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(destTrackIndex, buffer, bufferInfo)

                    if (!extractor.advance()) break
                }

                bufferInfo.set(0, 0, extractor.sampleTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(destTrackIndex, buffer, bufferInfo)
                destTrackIndex++
            }

            extractor.release()
            return destTrackIndex
        }

        fun merge( outputFile: String, inputVideoFile: String, inputAudioFile: String ): Boolean {
            var success = false
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            try {
                val muxer = MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var startDestTrackIndex = 0

                addTracks( muxer, inputVideoFile ) { mime -> mime.startsWith(VIDEO_MIME_PREFIX) }
                addTracks( muxer, inputAudioFile ) { mime -> !mime.startsWith(VIDEO_MIME_PREFIX) }

                muxer.start()

                startDestTrackIndex = copyTracks( muxer, inputVideoFile, buffer, startDestTrackIndex ) { mime -> mime.startsWith(VIDEO_MIME_PREFIX) }
                copyTracks( muxer, inputAudioFile, buffer, startDestTrackIndex ) { mime -> !mime.startsWith(VIDEO_MIME_PREFIX) }

                muxer.stop()
                muxer.release()
                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return success
        }
    }
}