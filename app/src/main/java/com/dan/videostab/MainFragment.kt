package com.dan.videostab

import android.content.ContentValues
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.view.*
import android.widget.AdapterView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.dan.videostab.databinding.MainFragmentBinding
import kotlinx.coroutines.*
import org.opencv.calib3d.Calib3d.estimateAffinePartial2D
import org.opencv.core.*
import org.opencv.core.CvType.CV_64F
import org.opencv.imgproc.Imgproc.*
import org.opencv.video.Video.calcOpticalFlowPyrLK
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio.*
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin


class MainFragment(activity: MainActivity) : AppFragment(activity) {
    companion object {
        const val INTENT_OPEN_VIDEO = 2

        private fun fixBorder(frame: Mat, crop: Double) {
            val t = getRotationMatrix2D(Point(frame.cols() / 2.0, frame.rows() / 2.0), 0.0, 1.0 + crop)
            warpAffine(frame, frame, t, frame.size())
        }

        fun show(activity: MainActivity) {
            activity.pushView("Video Stab", MainFragment(activity))
        }
    }

    private lateinit var binding: MainFragmentBinding
    private var videoUriOriginal: Uri? = null
    private var videoName  = ""
    private var videoProps: VideoProps? = null
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
    private val tmpInputVideo: String
        get() = "$tmpFolder/input.video"

    private fun videoPlay() {
        binding.videoOriginal.start()
        binding.videoStabilized.start()
    }

    private fun videoPause() {
        binding.videoOriginal.pause()
        binding.videoStabilized.pause()
    }

    private fun videoStop() {
        binding.videoOriginal.pause()
        binding.videoOriginal.seekTo(0)
        binding.videoStabilized.pause()
        binding.videoStabilized.seekTo(0)
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

        binding.play.setOnClickListener {
            videoPlay()
        }

        binding.pause.setOnClickListener {
            videoPause()
        }

        binding.stop.setOnClickListener {
            videoStop()
        }

        binding.buttonStabilize.setOnClickListener {
            handleStabilize()
        }

        binding.switchUseMask.setOnCheckedChangeListener { _, _ ->
            if (!firstFrame.empty() && !firstFrameMask.empty()) {
                openVideo()
            }
        }

        binding.buttonEditMask.setOnClickListener {
            if (binding.switchUseMask.isEnabled && binding.switchUseMask.isChecked && !firstFrame.empty()) {
                MaskEditFragment.show( activity, firstFrame, firstFrameMask ) {
                    openVideo()
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
            R.id.menuOpen -> {
                handleOpenVideo()
                return true
            }

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
            if (requestCode == INTENT_OPEN_VIDEO) {
                intent?.data?.let { uri -> openVideo(uri) }
                return
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

    private fun handleStabilize() {
        runAsync("Stabilize") {
            stabApplyAsync()
        }
    }

    private fun handleSave() {
        runAsync("Save") {
            saveAsync()
        }
    }

    private fun saveAsync() {
        var success = false
        val outputPath = getOutputPath()

        try {
            val inputStream = File(tmpOutputVideo).inputStream()
            val outputStream = File(outputPath).outputStream()
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()

            val values = ContentValues()
            @Suppress("DEPRECATION")
            values.put(MediaStore.Video.Media.DATA, outputPath)
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            activity.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            success = true
        } catch (e: Exception) {
            //TODO
        }

        showToast(if (success) "Saved: $outputPath" else "Failed !")
    }

    private fun createOutputFolder() {
        val file = File(Settings.SAVE_FOLDER)
        if (!file.exists()) file.mkdirs()
    }

    private fun getOutputPath(): String {
        createOutputFolder()

        var outputFilePath = ""
        var counter = 0

        while(counter < 999) {
            outputFilePath = Settings.SAVE_FOLDER + "/" + videoName + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + ".mp4"
            if (!File(outputFilePath).exists()) break
            counter++
        }

        return outputFilePath
    }

    private fun copyUriToTempFile() {
        val videoUri = this.videoUriOriginal ?: throw FileNotFoundException()
        val inputStream = activity.contentResolver.openInputStream(videoUri) ?: throw FileNotFoundException()
        val outputStream = File(tmpInputVideo).outputStream()
        inputStream.copyTo(outputStream)
        outputStream.close()
        inputStream.close()
    }


    private fun openVideoCapture( path: String ): VideoCapture {
        val videoCapture = VideoCapture(path)
        videoCapture.set( CAP_PROP_ORIENTATION_AUTO, 1.0 )
        return videoCapture
    }


    private fun stabAnalyzeAsync( newVideo: Boolean ) {
        try {
            val useMask = binding.switchUseMask.isEnabled && binding.switchUseMask.isChecked && !firstFrameMask.empty()
            videoProps = null
            videoTrajectory = null

            //OpenCV expects a file path not an URI so copy it
            if (newVideo) copyUriToTempFile()

            val videoInput = openVideoCapture(tmpInputVideo)
            if (!videoInput.isOpened) return

            val trajectoryX = mutableListOf<Double>()
            val trajectoryY = mutableListOf<Double>()
            val trajectoryA = mutableListOf<Double>()

            val videoWidth = videoInput.get(CAP_PROP_FRAME_WIDTH).toInt()
            val videoHeight = videoInput.get(CAP_PROP_FRAME_HEIGHT).toInt()
            val videoFrameRate = videoInput.get(CAP_PROP_FPS).toInt()
            val videoRotation = videoInput.get(CAP_PROP_ORIENTATION_META)

            var frameCounter = 0
            val readFrame = Mat()
            // 0 = read frame
            // 1, 2, 3 = new gray frame, analyse gray frame & analyze prev gray frame
            val frames = listOf( Mat(), Mat(), Mat() )
            var readIndex = 0
            var analyzeIndex = 1
            var analyzePrevIndex = 2
            var tmpIndex: Int

            val lastT = Mat()
            var x = 0.0
            var y = 0.0
            var a = 0.0

            while(true) {
                val readJob = GlobalScope.async(Dispatchers.Default) {
                    if (videoInput.read(readFrame)) {
                        if (firstFrame.empty()) firstFrame = readFrame.clone()
                        cvtColor(readFrame, frames[readIndex], COLOR_BGR2GRAY)
                    } else {
                        frames[readIndex].release()
                    }
                }

                if (!frames[analyzeIndex].empty()) {
                    frameCounter++
                    BusyDialog.show("Analyse frame: $frameCounter")

                    if (!frames[analyzePrevIndex].empty()) {
                        // Detect features in previous frame
                        val prevPts = MatOfPoint()
                        goodFeaturesToTrack(
                            frames[analyzePrevIndex],
                            prevPts,
                            200,
                            0.01,
                            30.0,
                            if (useMask) firstFrameMask else Mat())

                        // Calculate optical flow (i.e. track feature points)
                        val prevPts2f = MatOfPoint2f()
                        prevPts2f.fromList(prevPts.toList())
                        val framePts2f = MatOfPoint2f()
                        val status = MatOfByte()
                        val err = MatOfFloat()
                        calcOpticalFlowPyrLK(frames[analyzePrevIndex], frames[analyzeIndex], prevPts2f, framePts2f, status, err)

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
                        if(t.empty()) {
                            lastT.copyTo(t)
                        } else {
                            t.copyTo(lastT)
                        }

                        // Extract translation
                        val dx = t.get(0, 2)[0]
                        val dy = t.get(1, 2)[0]

                        // Extract rotation angle
                        val da = atan2(t.get(1, 0)[0], t.get(0, 0)[0])

                        x += dx
                        y += dy
                        a += da
                    }

                    trajectoryX.add(x)
                    trajectoryY.add(y)
                    trajectoryA.add(a)
                }

                runBlocking {
                    readJob.await()
                }

                if (frames[readIndex].empty()) break

                tmpIndex = readIndex
                readIndex = analyzePrevIndex
                analyzePrevIndex = analyzeIndex
                analyzeIndex = tmpIndex
            }

            videoInput.release()

            videoProps = VideoProps(
                    videoWidth,
                    videoHeight,
                    videoFrameRate,
                    videoRotation.toInt(),
                    frameCounter
            )

            videoTrajectory = Trajectory( trajectoryX.toList(), trajectoryY.toList(), trajectoryA.toList() )
        } catch (e: Exception) {
            //TODO
        }
    }

    private fun stabCalculateAutoCrop( transforms: Trajectory, videoProps: VideoProps ): Double {
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

            val extra = videoProps.height * sin(da)
            frameCropTop += extra
            frameCropBottom += extra

            val frameCropWidth = max(frameCropLeft, frameCropRight) * 2 / videoProps.width
            val frameCropHeight = max(frameCropTop, frameCropBottom) * 2 / videoProps.height
            val frameCrop = max(frameCropWidth, frameCropHeight)
            crop = max(crop, frameCrop)
        }

        crop += 0.02
        return crop
    }

    private fun stabGetCrop( transforms: Trajectory, videoProps: VideoProps ): Double {
        val cropOption = binding.crop.selectedItem as String
        val percentIndex = cropOption.indexOf('%')
        if (percentIndex > 0) return cropOption.substring(0, percentIndex).toInt() / 100.0
        return stabCalculateAutoCrop(transforms, videoProps)
    }

    private fun stabApplyAsync() {
        BusyDialog.show("Smooth movements")

        val trajectory = videoTrajectory ?: return
        val videoProps = this.videoProps ?: return

        TmpFiles(tmpFolder).delete("tmp_")

        var outputFrameRate: Int = videoProps.frameRate
        try {
            outputFrameRate = (binding.fps.selectedItem as String).toInt()
        } catch (e: Exception) {
            //TODO
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

        val crop = stabGetCrop(transforms, videoProps)

        val videoInput = openVideoCapture(tmpInputVideo)
        if (!videoInput.isOpened) throw FileNotFoundException()

        val videoOutput = VideoEncoder.create(
            tmpOutputVideo,
            outputFrameRate,
            videoProps.width,
            videoProps.height,
            Settings.ENCODER_H265 == settings.encoder
        )

        if (!videoOutput.isOpened) {
            throw FileNotFoundException(tmpOutputVideo)
        }

        val frame = Mat()
        val frameStabilized = Mat()
        val t = Mat(2, 3, CV_64F)

        for (index in transforms.x.indices) {
            BusyDialog.show("Stabilize frame ${index+1} / ${videoProps.frameCount}")

            if (!videoInput.read(frame)) break

            transforms.getTransform(index, t)
            warpAffine(frame, frameStabilized, t, frame.size())

            if (crop >= 0.001) fixBorder(frameStabilized, crop)
            videoOutput.write(frameStabilized)
        }

        videoOutput.release()
        videoInput.release()
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

    private fun openVideo(videoUri: Uri? = null) {
        videoProps = null
        videoTrajectory = null

        if (null != videoUri) {
            firstFrame = Mat()
            firstFrameMask = Mat()
            TmpFiles(tmpFolder).delete()
            videoUriOriginal = videoUri
            videoName = (DocumentFile.fromSingleUri(requireContext(), videoUri)?.name ?: "").split('.')[0]
            binding.videoOriginal.setVideoURI(videoUriOriginal)
            binding.switchUseMask.isChecked = false
            updateView()
        }

        runAsync("Prepare") { stabAnalyzeAsync(videoUri != null) }
    }

    private fun updateView() {
        if (!tmpOutputVideoExists) {
            binding.videoStabilized.setVideoURI(null)
            binding.layoutOriginal.visibility = View.VISIBLE
            binding.layoutStabilized.visibility = View.GONE
            binding.viewMode.isEnabled = false
            menuSave?.isEnabled = false
        } else {
            binding.videoStabilized.setVideoURI(File(tmpOutputVideo).toUri())
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

        val originalAvailable = null != videoUriOriginal
        binding.play.isEnabled = originalAvailable
        binding.pause.isEnabled = originalAvailable
        binding.stop.isEnabled = originalAvailable

        val canStabilize = videoTrajectory != null
        binding.switchUseMask.isEnabled = canStabilize
        binding.buttonEditMask.isEnabled = canStabilize
        binding.buttonStabilize.isEnabled = canStabilize
        binding.algorithm.isEnabled = canStabilize
        binding.seekBarStrength.isEnabled = canStabilize
        binding.crop.isEnabled = canStabilize
        binding.fps.isEnabled = canStabilize

        val videoProps = this.videoProps
        if (null != videoProps) {
            binding.info.text = "${videoProps.width} x ${videoProps.height}, fps: ${videoProps.frameRate}, $videoName"
        } else {
            binding.info.text = ""
        }
    }
}
