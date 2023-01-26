package com.dan.videostab

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.FileNotFoundException

class ImagesFramesInput(private val context: Context, inputUris: List<Uri>) : FramesInput() {

    companion object {
        fun fromFolder(context: Context, folderUri: Uri): ImagesFramesInput {
            val document = DocumentFile.fromTreeUri(context, folderUri) ?: throw FileNotFoundException()
            val files = document.listFiles()
                .filter { file -> file.type?.startsWith("image/") ?: false }
                .map { file -> file.uri }
            return ImagesFramesInput(context, files)
        }

        private fun loadImage(context: Context, uri: Uri): Mat {
            val inputStream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (null == bitmap) throw FileNotFoundException()
            val image = Mat()
            Utils.bitmapToMat(bitmap, image)
            return image
        }
    }

    private val _name: String
    private val _uris: List<Uri>
    private val _width: Int
    private val _height: Int
    private val _scaledImage = Mat()

    override val fps: Int
        get() = 30

    override val name: String
        get() = _name

    override val width: Int
        get() = _width

    override val height: Int
        get() = _height

    override val videoUri: Uri?
        get() = null

    override val imageUris: List<Uri>
        get() = _uris

    private fun sortUris(inputUris: List<Uri>): Pair<String, List<Uri>> {
        val urisWithNames = inputUris.map { uri ->
            val document = DocumentFile.fromSingleUri(context, uri) ?: throw FileNotFoundException()
            val name = document.name ?: throw FileNotFoundException()
            Pair(name, uri)
        }

        val sorted = urisWithNames.sortedBy { it.first }
        val sortedList = sorted.map{ pair -> pair.second }
        return Pair(urisWithNames[0].first, sortedList)
    }

    init {
        if (inputUris.isEmpty()) throw FileNotFoundException()
        val sortedResult = sortUris(inputUris)
        _name = fixName(sortedResult.first)
        _uris = sortedResult.second

        val firstImage = loadImage(context, _uris[0])
        val firstImageWidth = firstImage.width()
        val firstImageHeight = firstImage.height()

        //Try 1080p
        var outputWidth = 1920
        var outputHeight = 1080

        if (firstImageWidth < firstImageHeight) {
            val tmp = outputWidth
            outputWidth = outputHeight
            outputHeight = tmp
        }

        if (firstImageWidth > outputWidth || firstImageHeight > outputHeight) {
            // need 4K
            outputWidth *= 2
            outputHeight *= 2
        }

        _width = outputWidth
        _height = outputHeight
    }

    private fun scaleImage(inputImage: Mat): Mat {
        if (_width == inputImage.width() && _height == inputImage.height()) return inputImage

        if ((_width == inputImage.width() && _height > inputImage.height()) || (_width > inputImage.width() && _height == inputImage.height())) {
            return inputImage.submat( Rect((_width - inputImage.width())/ 2, (_height - inputImage.height())/2, _width, _height) )
        }

        val scaleUp = inputImage.width() < _width || inputImage.height() < _height
        val scaleAlgorithm = if (scaleUp) Imgproc.INTER_LANCZOS4 else Imgproc.INTER_AREA

        var newWidth = _width
        var newHeight = inputImage.height() * _width / inputImage.width()

        if (newHeight < _height) {
            newHeight = _height
            newWidth = inputImage.width() * _height / inputImage.height()
        }

        Imgproc.resize(
            inputImage,
            _scaledImage,
            Size(newWidth.toDouble(), newHeight.toDouble()),
            0.0,
            0.0,
            scaleAlgorithm
        )
        return _scaledImage.submat(Rect((_scaledImage.width() - _width) / 2, (_scaledImage.height() - _height) / 2, _width, _height))
    }

    override fun forEachFrame(callback: (Int, Int, Mat) -> Boolean) {
        var counter = 0
        for(uri in _uris) {
            if (!callback( counter, _uris.size, scaleImage(loadImage(context, uri)) )) break
            counter++
        }
        _scaledImage.release()
    }
}