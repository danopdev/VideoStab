package com.dan.videostab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio.*
import java.io.FileNotFoundException

class VideoFramesInput( private val context: Context, private val _videoUri: Uri) : FramesInput() {
    companion object {
        private fun open(context: Context, uri: Uri): VideoCapture {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException()
            val fd = pfd.detachFd()
            val videoCapture = VideoCapture(":$fd")
            pfd.close()
            if (!videoCapture.isOpened) throw FileNotFoundException()
            videoCapture.set(CAP_PROP_ORIENTATION_AUTO, 1.0 )
            return videoCapture
        }
    }

    private var _fps: Int = 0
    private val _name: String
    private var _width: Int = 0
    private var _height: Int = 0
    private var _size: Int = 0

    override val fps: Int
        get() = _fps

    override val name: String
        get() = _name

    override val width: Int
        get() = _width

    override val height: Int
        get() = _height

    override val size: Int
        get() = _size

    override val imageUris: List<Uri>?
        get() = null

    override val videoUri: Uri
        get() = _videoUri

    init {
        val document = DocumentFile.fromSingleUri(context, _videoUri) ?: throw FileNotFoundException()
        _name = fixName(document.name)
        withVideoInput { videoInput ->
            _fps = videoInput.get(CAP_PROP_FPS).toInt()
            _width = videoInput.get(CAP_PROP_FRAME_WIDTH).toInt()
            _height = videoInput.get(CAP_PROP_FRAME_HEIGHT).toInt()
            _size = videoInput.get(CAP_PROP_FRAME_COUNT).toInt()
        }

        if (_size <= 0) _size = VideoTools.countFrames(context, _videoUri)
    }

    override fun forEachFrame(callback: (Int, Int, Mat) -> Boolean) {
        var counter = 0
        withVideoInput { videoInput ->
            val frame = Mat()
            while(counter < _size && videoInput.read(frame)) {
                if (!callback(counter, _size, frame)) {
                    break
                }
                counter++
            }
        }
    }

    private fun withVideoInput(callback: (VideoCapture)->Unit) {
        val videoInput = open(context, _videoUri)
        callback(videoInput)
        videoInput.release()
    }
}