package com.dan.videostab

import android.content.Intent
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.AdapterView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
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
    private var firstFrame = Mat()
    private var firstFrameMask = Mat()
    private var menuSave: MenuItem? = null

    private val tmpFolder: String
        get() = requireContext().cacheDir.absolutePath
    private val tmpOutputVideo: String
        get() = "$tmpFolder/tmp_video.mp4"
    private val tmpOutputVideoExists: Boolean
        get() = File(tmpOutputVideo).exists()

    private fun videoPlay() {
        binding.videoOriginal.start()
        binding.videoStabilized.start()
        binding.imagesAsVideoOriginal.play()
    }

    private fun videoStop() {
        binding.videoOriginal.pause()
        binding.videoOriginal.seekTo(0)
        binding.videoStabilized.pause()
        binding.videoStabilized.seekTo(0)
        binding.imagesAsVideoOriginal.stop()
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

        binding.videoOriginal.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        binding.videoOriginal.setOnPreparedListener { newMediaPlayer ->
            newMediaPlayer.setVolume(0.0f, 0.0f)
        }

        binding.videoStabilized.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        binding.videoStabilized.setOnPreparedListener { newMediaPlayer ->
            newMediaPlayer.setVolume(0.0f, 0.0f)
        }

        binding.buttonOpenVideo.setOnClickListener { handleOpenVideo() }
        binding.buttonOpenImages.setOnClickListener { handleOpenImages() }
        binding.buttonOpenFolder.setOnClickListener { handleOpenFolder() }

        binding.play.setOnClickListener { videoPlay() }
        binding.stop.setOnClickListener { videoStop() }

        binding.buttonStabilize.setOnClickListener { handleStabilize() }
        binding.buttonAnalyse.setOnClickListener { stabAnalyse() }

        binding.buttonEditMask.setOnClickListener {
            if (!firstFrame.empty()) {
                MaskEditFragment.show( activity, firstFrame, firstFrameMask ) {
                    stabAnalyse()
                }
            }
        }

        binding.viewMode.setSelection(settings.viewMode)

        binding.viewMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                updateView()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
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
                settings.viewMode = binding.viewMode.selectedItemPosition
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


    private fun handleStabilize() {
        runAsync(TITLE_STABILIZE) {
            stabApplyAsync()
        }
    }

    private fun handleSave() {
        runAsync(TITLE_SAVE) {
            saveAsync()
        }
    }

    private fun saveAsync() {
        var success = false
        val framesInput = this.framesInput ?: return
        val outputPath = getOutputPath(framesInput)
        val inputVideoUri = framesInput.videoUri

        try {
            if (null != inputVideoUri && settings.keepAudio) {
                VideoMerge.merge(requireContext(), outputPath, tmpOutputVideo, inputVideoUri)
            } else {
                val inputStream = File(tmpOutputVideo).inputStream()
                val outputStream = File(outputPath).outputStream()
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
            }

            //Add it to gallery
            MediaScannerConnection.scanFile(context, arrayOf(outputPath), null, null)

            success = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        showToast(if (success) "Saved: $outputPath" else "Failed !")
    }

    private fun createOutputFolder() {
        val file = File(Settings.SAVE_FOLDER)
        if (!file.exists()) file.mkdirs()
    }

    private fun getOutputPath(framesInput: FramesInput): String {
        createOutputFolder()

        var outputFilePath = ""
        var counter = 0

        while(counter < 999) {
            outputFilePath = Settings.SAVE_FOLDER + "/" + framesInput.name + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + ".mp4"
            if (!File(outputFilePath).exists()) break
            counter++
        }

        return outputFilePath
    }

    private fun scaleForAnalyse(frame: Mat): Double {
        val maxSize = max(frame.width(), frame.height())
        if (maxSize < Settings.MAX_ANALYSE_SIZE) return 1.0

        val copyFrame = frame.clone()
        val scale = maxSize.toDouble() / Settings.MAX_ANALYSE_SIZE
        resize(copyFrame, frame, Size(copyFrame.width() / scale, copyFrame.height() / scale), 0.0, 0.0, INTER_AREA)
        return scale
    }

    private fun stabAnalyzeAsync() {
        try {
            videoTrajectory = null
            val framesInput = this.framesInput ?: return

            val trajectoryX = mutableListOf<Double>()
            val trajectoryY = mutableListOf<Double>()
            val trajectoryA = mutableListOf<Double>()

            var frameCounter = 0
            val frames = listOf( Mat(), Mat() )
            var currentIndex = 0
            var prevIndex = 1
            var tmpIndex: Int

            var x = 0.0
            var y = 0.0
            var a = 0.0

            framesInput.forEachFrame { index, size, readFrame ->
                cvtColor(readFrame, frames[currentIndex], COLOR_BGR2GRAY)
                val scale = scaleForAnalyse(frames[currentIndex])

                frameCounter++
                BusyDialog.show(TITLE_ANALYSE, index, size)

                if (!frames[prevIndex].empty()) {
                    // Detect features in previous frame
                    val prevPts = MatOfPoint()
                    goodFeaturesToTrack(
                        frames[prevIndex],
                        prevPts,
                        200,
                        0.01,
                        30.0,
                        firstFrameMask)

                    // Calculate optical flow (i.e. track feature points)
                    val prevPts2f = MatOfPoint2f()
                    prevPts2f.fromList(prevPts.toList())
                    val framePts2f = MatOfPoint2f()
                    val status = MatOfByte()
                    val err = MatOfFloat()
                    calcOpticalFlowPyrLK(frames[prevIndex], frames[currentIndex], prevPts2f, framePts2f, status, err)

                    // Filter only valid points
                    val prevPts2fList = prevPts2f.toList()
                    val framePts2fList = framePts2f.toList()
                    val statusList = status.toList()

                    val prevPts2fFilteredList = mutableListOf<Point>()
                    val framePts2fFilteredList = mutableListOf<Point>()

                    for( i in prevPts2fList.indices ) {
                        if (0.toByte() != statusList[i]) {
                            prevPts2fFilteredList.add(prevPts2fList[i])
                            framePts2fFilteredList.add(framePts2fList[i])
                        }
                    }

                    // Find transformation matrix
                    val prevPtsMat = MatOfPoint2f()
                    val framePtsMat = MatOfPoint2f()

                    prevPtsMat.fromList(prevPts2fFilteredList)
                    framePtsMat.fromList(framePts2fFilteredList)

                    val t: Mat = estimateAffinePartial2D(prevPtsMat, framePtsMat)

                    // In rare cases no transform is found.
                    // We'll just use the last known good transform.
                    if(!t.empty()) {
                        // Extract translation
                        val dx = t.get(0, 2)[0] * scale
                        val dy = t.get(1, 2)[0] * scale

                        // Extract rotation angle
                        val da = atan2(t.get(1, 0)[0], t.get(0, 0)[0])

                        x += dx
                        y += dy
                        a += da
                    }
                }

                trajectoryX.add(x)
                trajectoryY.add(y)
                trajectoryA.add(a)

                tmpIndex = currentIndex
                currentIndex = prevIndex
                prevIndex = tmpIndex

                true
            }

            videoTrajectory = Trajectory( trajectoryX.toList(), trajectoryY.toList(), trajectoryA.toList() )
        } catch (e: Exception) {
            //TODO
        }
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

    private fun stabApplyAsync() {
        val framesInput = this.framesInput ?: return
        val trajectory = videoTrajectory ?: return

        TmpFiles(tmpFolder).delete("tmp_")

        var outputFrameRate: Int = framesInput.fps
        try {
            outputFrameRate = (binding.fps.selectedItem as String).toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val movingAverageWindowSize = outputFrameRate * (binding.seekBarStrength.progress + 1)

        val newTrajectoryX: List<Double>
        val newTrajectoryY: List<Double>
        val newTrajectoryA: List<Double>

        when(binding.algorithm.selectedItemPosition) {
            Settings.ALGORITHM_GENERIC_B -> {
                newTrajectoryX = trajectory.x.movingAverage(movingAverageWindowSize)
                newTrajectoryY = trajectory.y.movingAverage(movingAverageWindowSize)
                newTrajectoryA = List(trajectory.size) { 0.0 }
            }

            Settings.ALGORITHM_STILL -> {
                newTrajectoryX = List(trajectory.size) { 0.0 }
                newTrajectoryY = newTrajectoryX
                newTrajectoryA = newTrajectoryX
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

        val transforms = Trajectory(
                trajectory.x.delta( newTrajectoryX ),
                trajectory.y.delta( newTrajectoryY ),
                trajectory.a.delta( newTrajectoryA ),
        )

        val crop = stabGetCrop(transforms, framesInput)

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

            transforms.getTransform(index, t)
            warpAffine(frame, frameStabilized, t, frame.size())

            if (crop >= 0.001) fixBorder(frameStabilized, crop)
            videoOutput.write(frameStabilized)

            true
        }

        videoOutput.release()
    }

    private fun runAsync(initialMessage: String, asyncTask: () -> Unit) {
        videoStop()

        GlobalScope.launch(Dispatchers.Default) {
            try {
                BusyDialog.show(initialMessage)
                asyncTask()
            } catch (e: Exception) {
                //TODO
            }

            runOnUiThread {
                updateView()
                BusyDialog.dismiss()
            }
        }
    }

    private fun setFramesInput(framesInput: FramesInput?) {
        this.framesInput = framesInput

        videoTrajectory = null
        firstFrame = Mat()
        firstFrameMask = Mat()
        TmpFiles(tmpFolder).delete()

        if (null == framesInput) {
            binding.videoOriginal.setVideoURI(null)
            binding.videoOriginal.visibility = View.VISIBLE
            binding.imagesAsVideoOriginal.setImages(null)
            binding.imagesAsVideoOriginal.visibility = View.GONE
        } else {
            val imageUris = framesInput.imageUris
            if (null == imageUris) {
                binding.videoOriginal.setVideoURI(framesInput.videoUri)
                binding.videoOriginal.visibility = View.VISIBLE
                binding.imagesAsVideoOriginal.setImages(null)
                binding.imagesAsVideoOriginal.visibility = View.GONE
            } else {
                binding.videoOriginal.setVideoURI(null)
                binding.videoOriginal.visibility = View.GONE
                binding.imagesAsVideoOriginal.setImages(imageUris, framesInput.fps)
                binding.imagesAsVideoOriginal.visibility = View.VISIBLE
            }

            firstFrame = framesInput.firstFrame()
            scaleForAnalyse(firstFrame)
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

    private fun stabAnalyse() {
        videoTrajectory = null
        runAsync(TITLE_ANALYSE) { stabAnalyzeAsync() }
    }

    private fun updateView() {
        if (!tmpOutputVideoExists) {
            binding.videoStabilized.setVideoURI(null)
            binding.layoutOriginal.visibility = View.VISIBLE
            binding.layoutStabilized.visibility = View.GONE
            binding.viewMode.isEnabled = false
            menuSave?.isEnabled = false
        } else {
            try {
                binding.videoStabilized.setVideoURI(File(tmpOutputVideo).toUri())
            } catch (e: Exception) {
                binding.videoStabilized.setVideoURI(null)
            }
            binding.layoutViewMode.visibility = View.VISIBLE

            when (binding.viewMode.selectedItemPosition) {
                Settings.VIEW_MODE_ORIGINAL -> {
                    binding.layoutOriginal.visibility = View.VISIBLE
                    binding.layoutStabilized.visibility = View.GONE
                }
                Settings.VIEW_MODE_STABILIZED -> {
                    binding.layoutOriginal.visibility = View.GONE
                    binding.layoutStabilized.visibility = View.VISIBLE
                }
                else -> {
                    binding.layoutPlayer.orientation =
                        if (Settings.VIEW_MODE_SPLIT_VERTICAL == binding.viewMode.selectedItemPosition) LinearLayout.VERTICAL
                        else LinearLayout.HORIZONTAL
                    binding.layoutOriginal.visibility = View.VISIBLE
                    binding.layoutStabilized.visibility = View.VISIBLE
                }
            }

            menuSave?.isEnabled = true
            binding.viewMode.isEnabled = true
        }

        val framesInput = this.framesInput
        val originalAvailable = null != framesInput
        binding.play.isEnabled = originalAvailable
        binding.stop.isEnabled = originalAvailable
        binding.buttonEditMask.isEnabled = originalAvailable

        val canStabilize = videoTrajectory != null
        binding.buttonStabilize.isEnabled = canStabilize
        binding.algorithm.isEnabled = canStabilize
        binding.seekBarStrength.isEnabled = canStabilize
        binding.crop.isEnabled = canStabilize
        binding.fps.isEnabled = canStabilize
        binding.buttonAnalyse.isEnabled = originalAvailable && !canStabilize

        if (null != framesInput) {
            binding.info.text = "${framesInput.width} x ${framesInput.height}, fps: ${framesInput.fps}, ${framesInput.name}"
        } else {
            binding.info.text = ""
        }
    }
}
