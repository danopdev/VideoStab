package com.dan.videostab

import android.content.Intent
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import com.dan.videostab.databinding.MainFragmentBinding
import kotlinx.coroutines.*
import org.opencv.calib3d.Calib3d.estimateAffinePartial2D
import org.opencv.core.*
import org.opencv.core.CvType.CV_64F
import org.opencv.imgproc.Imgproc.*
import org.opencv.video.Video.calcOpticalFlowPyrLK
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin


class MainFragment(activity: MainActivity) : AppFragment(activity) {
    companion object {
        const val TITLE_ANALYSE = "Analyse"
        const val TITLE_STABILIZE = "Stabilize"
        const val TITLE_SAVE = "Save"

        const val INTENT_OPEN_VIDEO = 2
        const val INTENT_OPEN_IMAGES = 3
        const val INTENT_OPEN_FOLDER = 4

        private fun fixBorder(frame: Mat, crop: Double) {
            val t = getRotationMatrix2D(Point(frame.cols() / 2.0, frame.rows() / 2.0), 0.0, 1.0 + crop)
            warpAffine(frame, frame, t, frame.size())
        }

        fun show(activity: MainActivity) {
            activity.pushView("Video Stab", MainFragment(activity))
        }
    }

    private lateinit var binding: MainFragmentBinding
    private var framesInput: FramesInput? = null
    private var videoTrajectory: Trajectory? = null
    private var videoTrajectoryStill: Trajectory? = null
    private var firstFrame = Mat()
    private var firstFrameMask = Mat()
    private var menuSave: MenuItem? = null
    private var outputParams: OutputParams? = null
    private var maskId: Int = 0

    private val tmpFolder: String
        get() = requireContext().cacheDir.absolutePath
    private val tmpOutputVideo: String
        get() = "$tmpFolder/tmp_video.mp4"
    private val tmpOutputVideoExists: Boolean
        get() = File(tmpOutputVideo).exists()

    private fun videoPlayOriginal() {
        val framesInput = this.framesInput ?: return

        if (null != framesInput.imageUris) {
            binding.video.visibility = View.GONE
            binding.imagesAsVideo.visibility = View.VISIBLE
            binding.video.setVideoURI(null)
            binding.imagesAsVideo.setImages(framesInput.imageUris)
            binding.imagesAsVideo.play()
            return
        }

        binding.video.visibility = View.VISIBLE
        binding.imagesAsVideo.visibility = View.GONE
        binding.video.setVideoURI(framesInput.videoUri)
        binding.imagesAsVideo.setImages(null)
        binding.video.start()
    }

    private fun videoPlayStabilized(stabilizeIfNeeded: Boolean) {
        if (null == framesInput) return

        val outputParams = getCurrentOutputParams()
        val changes = outputParams.compareWith((this.outputParams))
        if (!tmpOutputVideoExists || OutputParams.COMPARE_NOT_CHANGED != changes) {
            if (stabilizeIfNeeded) handleStabilize(outputParams)
            return
        }

        binding.video.visibility = View.VISIBLE
        binding.imagesAsVideo.visibility = View.GONE
        binding.video.setVideoPath(tmpOutputVideo)
        binding.imagesAsVideo.setImages(null)
        binding.video.start()
    }

    private fun videoStop() {
        binding.video.pause()
        binding.video.seekTo(0)
        binding.imagesAsVideo.stop()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        TmpFiles(tmpFolder).delete()

        binding = MainFragmentBinding.inflate(inflater)

        binding.algorithm.setSelection(settings.algorithm)
        binding.seekBarStrength.progress = settings.strength

        binding.video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        binding.video.setOnPreparedListener { newMediaPlayer -> newMediaPlayer.setVolume(0.0f, 0.0f) }

        binding.buttonOpenVideo.setOnClickListener { handleOpenVideo() }
        binding.buttonOpenImages.setOnClickListener { handleOpenImages() }
        binding.buttonOpenFolder.setOnClickListener { handleOpenFolder() }

        binding.playOriginal.setOnClickListener { videoPlayOriginal() }
        binding.playStabilized.setOnClickListener { videoPlayStabilized(true) }
        binding.stop.setOnClickListener { videoStop() }

        binding.editMask.setOnClickListener {
            if (!firstFrame.empty()) {
                MaskEditFragment.show( activity, firstFrame, firstFrameMask ) {
                    videoTrajectory = null
                    videoTrajectoryStill = null
                    maskId++
                }
            }
        }

        updateView()

        val intent = activity.intent
        var initialUri: Uri? = null

        if (null != intent && null != intent.action) {
            if (Intent.ACTION_SEND == intent.action) {
                val extraStream = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)
                if (null != extraStream) {
                    initialUri = extraStream as Uri
                }
            } else if(Intent.ACTION_VIEW == intent.action){
                initialUri = intent.data
            }
        }

        if (null != initialUri) openVideo(initialUri)

        setHasOptionsMenu(true)

        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        inflater.inflate(R.menu.app_menu, menu)

        menuSave = menu.findItem(R.id.menuSave)

        updateView()
    }

    override fun onDestroyOptionsMenu() {
        menuSave = null
        super.onDestroyOptionsMenu()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menuSave -> {
                handleSave()
                settings.algorithm = binding.algorithm.selectedItemPosition
                settings.strength = binding.seekBarStrength.progress
                settings.saveProperties()
                return true
            }

            R.id.menuSettings -> {
                SettingsFragment.show(activity)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == AppCompatActivity.RESULT_OK) {
            when (requestCode) {
                INTENT_OPEN_VIDEO -> {
                    intent?.data?.let { uri -> openVideo(uri) }
                    return
                }

                INTENT_OPEN_IMAGES -> {
                    intent?.clipData?.let { clipData ->
                        val uriList = mutableListOf<Uri>()
                        val count = clipData.itemCount
                        for (i in 0 until count) {
                            uriList.add(clipData.getItemAt(i).uri)
                        }
                        openImages(uriList.toList())
                    }
                    return
                }

                INTENT_OPEN_FOLDER -> {
                    intent?.data?.let { uri -> openFolder(uri) }
                    return
                }
            }
        }

        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun handleOpenVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .putExtra("android.content.extra.SHOW_ADVANCED", true)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                .putExtra(Intent.EXTRA_TITLE, "Select video")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_VIDEO)
    }

    private fun handleOpenImages() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_TITLE, "Select images")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_IMAGES)
    }

    private fun handleOpenFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            .putExtra(Intent.EXTRA_TITLE, "Select folder")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, INTENT_OPEN_FOLDER)
    }


    private fun handleStabilize(outputParams: OutputParams) {
        val framesInput = this.framesInput ?: return
        runAsync(TITLE_STABILIZE, framesInput.size, true) {
            stabApplyAsync(outputParams)
        }
    }

    private fun handleSave() {
        runAsync(TITLE_SAVE, -1, false) {
            saveAsync()
        }
    }

    private fun saveAsync() {
        var success = false
        val framesInput = this.framesInput ?: return
        val outputFile = getOutputFile(framesInput)
        val inputVideoUri = framesInput.videoUri

        try {
            if (null != inputVideoUri && settings.keepAudio) {
                VideoTools.combineVideoAndAudio(requireContext(), outputFile, Uri.fromFile(File(tmpOutputVideo)), inputVideoUri)
            } else {
                val inputStream = File(tmpOutputVideo).inputStream()
                val outputStream = outputFile.outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            }

            //Add it to gallery
            MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), null, null)

            success = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        showToast(if (success) "Saved: ${outputFile.absolutePath}" else "Failed !")
    }

    private fun createOutputFolder() {
        if (!Settings.SAVE_FOLDER.exists()) Settings.SAVE_FOLDER.mkdirs()
    }

    private fun getOutputFile(framesInput: FramesInput): File {
        createOutputFolder()

        var counter = 0
        while(true) {
            val outputFile = File(Settings.SAVE_FOLDER, framesInput.name + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + ".mp4")
            if (counter >= 999 || !outputFile.exists()) return outputFile
            counter++
        }
    }

    private fun getGoodFeaturesToTrack(frame: Mat, points2f: MatOfPoint2f) {
        val points = MatOfPoint()

        goodFeaturesToTrack(
            frame,
            points,
            200,
            0.01,
            30.0,
            firstFrameMask)

        points2f.fromList(points.toList())
    }

    private fun calculateTransformation(prevFrame: Mat, prevFramePoints: MatOfPoint2f, newFrame: Mat): Triple<Double, Double, Double> {
        // Calculate optical flow (i.e. track feature points)
        val newFramePoints = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        calcOpticalFlowPyrLK(prevFrame, newFrame, prevFramePoints, newFramePoints, status, err)

        // Filter only valid points
        val prevFramePointsList = prevFramePoints.toList()
        val newFramePointsList = newFramePoints.toList()
        val statusList = status.toList()

        val prevFramePointsFilteredList = mutableListOf<Point>()
        val newFramePointsFilteredList = mutableListOf<Point>()

        for( i in prevFramePointsList.indices ) {
            if (0.toByte() != statusList[i]) {
                prevFramePointsFilteredList.add(prevFramePointsList[i])
                newFramePointsFilteredList.add(newFramePointsList[i])
            }
        }

        // Find transformation matrix
        val prevFramePointsMat = MatOfPoint2f()
        val newFramePointsMat = MatOfPoint2f()

        prevFramePointsMat.fromList(prevFramePointsFilteredList)
        newFramePointsMat.fromList(newFramePointsFilteredList)

        val transformation = estimateAffinePartial2D(prevFramePointsMat, newFramePointsMat)

        if (transformation.empty()) {
            return Triple(0.0, 0.0, 0.0)
        }

        // Extract translation
        val dx = transformation.get(0, 2)[0]
        val dy = transformation.get(1, 2)[0]

        // Extract rotation angle
        val da = atan2(transformation.get(1, 0)[0], transformation.get(0, 0)[0])

        return Triple(dx, dy, da)
    }

    private fun stabAnalyzeAsyncStill(framesInput: FramesInput) {
        videoTrajectoryStill = null

        val trajectoryX = mutableListOf<Double>()
        val trajectoryY = mutableListOf<Double>()
        val trajectoryA = mutableListOf<Double>()

        val firstFramePoints = MatOfPoint2f()
        val firstFrameGray = Mat()
        val frameGray = Mat()

        framesInput.forEachFrame { index, size, readFrame ->
            BusyDialog.show(TITLE_ANALYSE, index, size)

            if (0 == index) {
                cvtColor(readFrame, firstFrameGray, COLOR_BGR2GRAY)
                getGoodFeaturesToTrack(firstFrameGray, firstFramePoints)
                trajectoryX.add(0.0)
                trajectoryY.add(0.0)
                trajectoryA.add(0.0)
            } else {
                cvtColor(readFrame, frameGray, COLOR_BGR2GRAY)
                val deltas = calculateTransformation(firstFrameGray, firstFramePoints, frameGray)
                trajectoryX.add(-deltas.first)
                trajectoryY.add(-deltas.second)
                trajectoryA.add(-deltas.third)
            }

            true
        }

        videoTrajectoryStill = Trajectory( trajectoryX.toList(), trajectoryY.toList(), trajectoryA.toList() )

        firstFramePoints.release()
        firstFrameGray.release()
        frameGray.release()
    }

    private fun stabAnalyzeAsyncMoving(framesInput: FramesInput) {
        videoTrajectory = null

        val trajectoryX = mutableListOf<Double>()
        val trajectoryY = mutableListOf<Double>()
        val trajectoryA = mutableListOf<Double>()

        val prevFramePoints = MatOfPoint2f()
        var prevFrameGray = Mat()
        var x = 0.0
        var y = 0.0
        var a = 0.0

        framesInput.forEachFrame { index, size, readFrame ->
            BusyDialog.show(TITLE_ANALYSE, index, size)

            if (0 == index) {
                cvtColor(readFrame, prevFrameGray, COLOR_BGR2GRAY)
            } else {
                getGoodFeaturesToTrack(prevFrameGray, prevFramePoints)

                val frameGray = Mat()
                cvtColor(readFrame, frameGray, COLOR_BGR2GRAY)
                val deltas = calculateTransformation(prevFrameGray, prevFramePoints, frameGray)
                x += deltas.first
                y += deltas.second
                a += deltas.third
                prevFrameGray.release()
                prevFrameGray = frameGray
            }

            trajectoryX.add(x)
            trajectoryY.add(y)
            trajectoryA.add(a)

            true
        }

        prevFrameGray.release()
        prevFramePoints.release()
        videoTrajectory = Trajectory( trajectoryX.toList(), trajectoryY.toList(), trajectoryA.toList() )
    }

    private fun stabCalculateAutoCrop( transforms: Trajectory, framesInput: FramesInput ): Double {
        var crop = 0.0

        for (index in transforms.x.indices) {
            val dx = transforms.x[index]
            val dy = transforms.y[index]
            val da = transforms.a[index]

            var frameCropLeft = 0.0
            var frameCropRight = 0.0
            var frameCropTop = 0.0
            var frameCropBottom = 0.0

            if (dx >= 0) {
                frameCropLeft = dx
            } else {
                frameCropRight = -dx
            }

            if (dy >= 0) {
                frameCropTop = dy
            } else {
                frameCropBottom = -dy
            }

            val extra = framesInput.height * sin(da)
            frameCropTop += extra
            frameCropBottom += extra

            val frameCropWidth = max(frameCropLeft, frameCropRight) * 2 / framesInput.width
            val frameCropHeight = max(frameCropTop, frameCropBottom) * 2 / framesInput.height
            val frameCrop = max(frameCropWidth, frameCropHeight)
            crop = max(crop, frameCrop)
        }

        crop += 0.02
        return crop
    }

    private fun stabGetCrop( transforms: Trajectory, framesInput: FramesInput ): Double {
        val cropOption = binding.crop.selectedItem as String
        val percentIndex = cropOption.indexOf('%')
        if (percentIndex > 0) return cropOption.substring(0, percentIndex).toInt() / 100.0
        return stabCalculateAutoCrop(transforms, framesInput)
    }

    private fun stabApplyAsync(outputParams: OutputParams) {
        this.outputParams = null
        val framesInput = this.framesInput ?: return

        val algorithm = outputParams.get(OutputParams.KEY_ALGORITHM)
        var trajectory = if (Settings.ALGORITHM_STILL == algorithm) {
            if (null == videoTrajectoryStill) stabAnalyzeAsyncStill(framesInput)
            videoTrajectoryStill
        } else {
            if (null == videoTrajectory) stabAnalyzeAsyncMoving(framesInput)
            videoTrajectory
        }

        if (null == trajectory) return

        TmpFiles(tmpFolder).delete("tmp_")

        val outputFrameRate = outputParams.get(OutputParams.KEY_FPS)

        if (Settings.ALGORITHM_STILL != algorithm) {
            val movingAverageWindowSize = outputFrameRate * (binding.seekBarStrength.progress + 1)

            val newTrajectoryX: List<Double>
            val newTrajectoryY: List<Double>
            val newTrajectoryA: List<Double>

            when (algorithm) {
                Settings.ALGORITHM_GENERIC_B -> {
                    newTrajectoryX = trajectory.x.movingAverage(movingAverageWindowSize)
                    newTrajectoryY = trajectory.y.movingAverage(movingAverageWindowSize)
                    newTrajectoryA = List(trajectory.size) { 0.0 }
                }

                Settings.ALGORITHM_HORIZONTAL_PANNING -> {
                    newTrajectoryX = trajectory.x.distribute()
                    newTrajectoryY = List(trajectory.size) { 0.0 }
                    newTrajectoryA = newTrajectoryY
                }

                Settings.ALGORITHM_HORIZONTAL_PANNING_B -> {
                    newTrajectoryX = trajectory.x.distribute()
                    newTrajectoryY = List(trajectory.size) { 0.0 }
                    newTrajectoryA = trajectory.a.movingAverage(movingAverageWindowSize)
                }

                Settings.ALGORITHM_VERTICAL_PANNING -> {
                    newTrajectoryX = List(trajectory.size) { 0.0 }
                    newTrajectoryY = trajectory.y.distribute()
                    newTrajectoryA = newTrajectoryX
                }

                Settings.ALGORITHM_VERTICAL_PANNING_B -> {
                    newTrajectoryX = List(trajectory.size) { 0.0 }
                    newTrajectoryY = trajectory.y.distribute()
                    newTrajectoryA = trajectory.a.movingAverage(movingAverageWindowSize)
                }

                Settings.ALGORITHM_PANNING -> {
                    newTrajectoryX = trajectory.x.distribute()
                    newTrajectoryY = trajectory.y.distribute()
                    newTrajectoryA = List(trajectory.size) { 0.0 }
                }

                Settings.ALGORITHM_PANNING_B -> {
                    newTrajectoryX = trajectory.x.distribute()
                    newTrajectoryY = trajectory.y.distribute()
                    newTrajectoryA = trajectory.a.movingAverage(movingAverageWindowSize)
                }

                Settings.ALGORITHM_NO_ROTATION -> {
                    newTrajectoryX = trajectory.x
                    newTrajectoryY = trajectory.y
                    newTrajectoryA = List(trajectory.size) { 0.0 }
                }

                else -> {
                    newTrajectoryX = trajectory.x.movingAverage(movingAverageWindowSize)
                    newTrajectoryY = trajectory.y.movingAverage(movingAverageWindowSize)
                    newTrajectoryA = trajectory.a.movingAverage(movingAverageWindowSize)
                }
            }

            trajectory = Trajectory(
                trajectory.x.delta(newTrajectoryX),
                trajectory.y.delta(newTrajectoryY),
                trajectory.a.delta(newTrajectoryA),
            )
        }

        val crop = stabGetCrop(trajectory, framesInput)

        val videoOutput = VideoEncoder.create(
            tmpOutputVideo,
            outputFrameRate,
            framesInput.width,
            framesInput.height,
            0,
            settings.encodeH265
        )

        if (!videoOutput.isOpened) {
            throw FileNotFoundException(tmpOutputVideo)
        }

        val frameStabilized = Mat()
        val t = Mat(2, 3, CV_64F)

        framesInput.forEachFrame { index, size, frame ->
            BusyDialog.show(TITLE_STABILIZE, index, size)

            trajectory.getTransform(index, t)
            warpAffine(frame, frameStabilized, t, frame.size())

            if (crop >= 0.001) fixBorder(frameStabilized, crop)
            videoOutput.write(frameStabilized)

            true
        }

        videoOutput.release()
        frameStabilized.release()

        this.outputParams = outputParams
    }

    private fun runAsync(initialMessage: String, size: Int, playStabilizedOnFinish: Boolean, asyncTask: () -> Unit) {
        videoStop()

        GlobalScope.launch(Dispatchers.Default) {
            try {
                BusyDialog.show(initialMessage, 0, size)
                asyncTask()
            } catch (e: Exception) {
                //TODO
            }

            runOnUiThread {
                updateView()
                BusyDialog.dismiss()
                if (playStabilizedOnFinish) videoPlayStabilized(false)
            }
        }
    }

    private fun getCurrentOutputParams(): OutputParams {
        val outputParams = OutputParams()

        outputParams.set(OutputParams.KEY_ALGORITHM, binding.algorithm.selectedItemPosition)
        outputParams.set(OutputParams.KEY_STRENGTH, if (Settings.ALGORITHM_STILL == binding.algorithm.selectedItemPosition) 0 else binding.seekBarStrength.progress)
        outputParams.set(OutputParams.KEY_CROP, binding.crop.selectedItemPosition)
        outputParams.set(OutputParams.KEY_ALIGN, maskId)

        var outputFps: Int = framesInput?.fps ?: 30
        try {
            outputFps = (binding.fps.selectedItem as String).toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        outputParams.set(OutputParams.KEY_FPS, outputFps)

        return outputParams
    }

    private fun setFramesInput(framesInput: FramesInput?) {
        this.framesInput = framesInput

        maskId = 0
        outputParams = null
        videoTrajectory = null
        videoTrajectoryStill = null
        firstFrame.release()
        firstFrameMask.release()
        TmpFiles(tmpFolder).delete()

        binding.video.setVideoURI(null)
        binding.video.visibility = View.VISIBLE
        binding.imagesAsVideo.setImages(null)
        binding.imagesAsVideo.visibility = View.GONE

        if (null != framesInput) {
            firstFrame = framesInput.firstFrame()
        }
    }

    private fun openVideo(videoUri: Uri) {
        try {
            setFramesInput(VideoFramesInput(requireContext(), videoUri))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateView()
    }

    private fun openImages(uris: List<Uri>) {
        try {
            setFramesInput(ImagesFramesInput(requireContext(), uris))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateView()
    }

    private fun openFolder(folderUri: Uri) {
        try {
            setFramesInput(ImagesFramesInput.fromFolder(requireContext(), folderUri))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateView()
    }

    private fun updateView() {
        val framesInput = this.framesInput
        val enabled = null != framesInput

        binding.algorithm.isEnabled = enabled
        binding.seekBarStrength.isEnabled = enabled
        binding.crop.isEnabled = enabled
        binding.fps.isEnabled = enabled
        binding.playOriginal.isEnabled = enabled
        binding.playStabilized.isEnabled = enabled
        binding.stop.isEnabled = enabled
        binding.editMask.isEnabled = enabled

        menuSave?.isEnabled = tmpOutputVideoExists

        if (null == framesInput) {
            binding.video.setVideoURI(null)
            binding.imagesAsVideo.setImages(null)
            binding.video.visibility = View.VISIBLE
            binding.imagesAsVideo.visibility = View.GONE
            binding.info.text = ""
        } else {
            binding.info.text = "${framesInput.width} x ${framesInput.height}, fps: ${framesInput.fps}, ${framesInput.name}"
        }
    }
}
