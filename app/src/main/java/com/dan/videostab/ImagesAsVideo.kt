package com.dan.videostab

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import java.util.*
import kotlin.concurrent.timer



open class ImagesAsVideo @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var _images: List<Uri>? = null
    private var _fps: Int = 5
    private var _index: Int = 0
    private var _bitmap: Bitmap? = null
    private val _rect = Rect()
    private var _timer: Timer? = null
    private var _bitmapDisplayed = false

    fun setImages(images: List<Uri>?, fps: Int = 0) {
        stopTimer()
        _images = images
        _index = 0
        _fps = when {
            fps < 5 -> 5
            fps > 30 -> 30
            else -> fps
        }
        updateBitmap()
    }

    fun play() {
        _index = 0
        updateBitmap()
        startTimer()
    }

    fun stop() {
        stopTimer()
        _index = 0
        updateBitmap()
    }

    private fun startTimer() {
        val period = 1000L / _fps
        _timer = timer(null, false, period, period) {
            if (_bitmapDisplayed) {
                nextFrame()
            }
        }
    }

    private fun stopTimer() {
        _timer?.cancel()
        _timer = null
    }

    private fun nextFrame() {
        val images = _images
        val index = _index

        if (null != images && images.isNotEmpty() && index >= 0 && index < (images.size-1)) {
            _index++
            updateBitmap()
        } else {
            stopTimer()
        }
    }

    private fun updateBitmap() {
        _bitmap = null

        val images = _images
        val context = this.context
        if (null != context && null != images && images.isNotEmpty()) {
            val index = _index
            if (index >= 0 && index < images.size) {
                try {
                    val inputStream = context.contentResolver.openInputStream(images[index])
                    if (null != inputStream) {
                        _bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        _bitmapDisplayed = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        _bitmapDisplayed = true
        if (null == canvas) return

        canvas.drawRGB(0,0,0)

        val bitmap = _bitmap ?: return

        var outputWidth = width
        var outputHeight = width * bitmap.height / bitmap.width

        if (outputHeight > height) {
            outputHeight = height
            outputWidth = height * bitmap.width / bitmap.height
        }

        val x = (width - outputWidth) / 2
        val y = (height - outputHeight) / 2
        _rect.set(x, y, x + outputWidth, y + outputHeight)

        canvas.drawBitmap(bitmap, null, _rect, null)
    }
}