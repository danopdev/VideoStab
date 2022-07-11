package com.dan.videostab

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.dan.videostab.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d.estimateAffinePartial2D
import org.opencv.core.*
import org.opencv.core.CvType.CV_64F
import org.opencv.imgproc.Imgproc.*
import org.opencv.video.Video.calcOpticalFlowPyrLK
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.VideoWriter
import org.opencv.videoio.Videoio.*
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.atan2


class MainActivity : AppCompatActivity() {
    companion object {
        val PERMISSIONS = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        const val REQUEST_PERMISSIONS = 1
        const val INTENT_OPEN_VIDEO = 2

        const val VIEW_MODE_ORIGINAL = 0
        const val VIEW_MODE_STABILIZED = 1
        const val VIEW_MODE_SPLIT_HORIZONTAL = 2
        const val VIEW_MODE_SPLIT_VERTICAL = 3

        const val SAVE_FOLDER = "/storage/emulated/0/VideoStab"
        const val TEMP_INPUT_FILE_NAME = "input.video"

        private fun fixBorder(frame: Mat) {
            val t = getRotationMatrix2D(Point(frame.cols() / 2.0, frame.rows() / 2.0), 0.0, 1.04)
            warpAffine(frame, frame, t, frame.size())
        }
    }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var videoUriOriginal: Uri? = null
    private var videoUriStabilized: Uri? = null
    private var videoName  = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!askPermissions()) onPermissionsAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSIONS -> handleRequestPermissions(grantResults)
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun exitApp() {
        setResult(0)
        finish()
    }

    private fun fatalError(msg: String) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(msg)
                .setIcon(android.R.drawable.stat_notify_error)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> exitApp() }
                .show()
    }

    private fun askPermissions(): Boolean {
        for (permission in PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
                return true
            }
        }

        return false
    }

    private fun handleRequestPermissions(grantResults: IntArray) {
        var allowedAll = grantResults.size >= PERMISSIONS.size

        if (grantResults.size >= PERMISSIONS.size) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allowedAll = false
                    break
                }
            }
        }

        if (allowedAll) onPermissionsAllowed()
        else fatalError("You must allow permissions !")
    }

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

    private fun onPermissionsAllowed() {
        if (!OpenCVLoader.initDebug()) fatalError("Failed to initialize OpenCV")

        BusyDialog.create(this)

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

        binding.viewMode.setSelection(VIEW_MODE_SPLIT_HORIZONTAL)

        binding.viewMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                updateView()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }

        updateView()

        setContentView(binding.root)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.app_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menuOpenVideo -> handleOpenVideo()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (resultCode == RESULT_OK) {
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

    private fun createOutputFolder() {
        val file = File(SAVE_FOLDER)
        if (!file.exists()) file.mkdirs()
    }

    private fun getOutputPath(): String {
        createOutputFolder()

        var outputFilePath = ""
        var counter = 0

        while(counter < 999) {
            outputFilePath = SAVE_FOLDER + "/" + videoName + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + ".avi"
            if (!File(outputFilePath).exists()) break
            counter++
        }

        return outputFilePath
    }

    private fun copyUriToTempFile(videoUri: Uri): String? {
        val tmpFilePath = applicationContext.filesDir.absolutePath + "/" + TEMP_INPUT_FILE_NAME
        val inputStream = contentResolver.openInputStream(videoUri) ?: return null
        val outputStream = File(tmpFilePath).outputStream()
        inputStream.copyTo(outputStream)
        return tmpFilePath
    }


    private fun openVideoCapture( path: String ): VideoCapture {
        val videoCapture = VideoCapture(path)
        videoCapture.set( CAP_PROP_ORIENTATION_AUTO, 1.0 )
        return videoCapture
    }


    private fun stabAnalyze(path: String): Pair<List<Transform>, VideoProps> {
        val videoInput = openVideoCapture(path)
        if (!videoInput.isOpened) throw FileNotFoundException()

        val transforms = mutableListOf<Transform>()

        val videoWidth = videoInput.get(CAP_PROP_FRAME_WIDTH).toInt()
        val videoHeight = videoInput.get(CAP_PROP_FRAME_HEIGHT).toInt()
        val videoFrameRate = videoInput.get(CAP_PROP_FPS).toInt()
        val videoRotation = videoInput.get(CAP_PROP_ORIENTATION_META)

        var frameCounter = 0
        val frameGray = Mat()
        val prevGray = Mat()
        val lastT = Mat()
        val frame = Mat()

        while(videoInput.read(frame)) {
            frameCounter++
            BusyDialog.show("Analyse frame: $frameCounter")

            cvtColor(frame, frameGray, COLOR_BGR2GRAY);

            if (!prevGray.empty()) {
                // Detect features in previous frame
                val prevPts = MatOfPoint()
                goodFeaturesToTrack(prevGray, prevPts, 200, 0.01, 30.0);

                // Calculate optical flow (i.e. track feature points)
                val prevPts2f = MatOfPoint2f()
                prevPts2f.fromList(prevPts.toList())
                val framePts2f = MatOfPoint2f()
                val status = MatOfByte()
                val err = MatOfFloat()
                calcOpticalFlowPyrLK(prevGray, frameGray, prevPts2f, framePts2f, status, err)

                // Filter only valid points
                val prevPts2fList = prevPts2f.toList()
                val framePts2fList = prevPts2f.toList()
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
                    t.copyTo(lastT);
                }

                // Extract traslation
                val dx = t.get(0, 2)[0]
                val dy = t.get(1, 2)[0]

                // Extract rotation angle
                val da = atan2(t.get(1, 0)[0], t.get(0, 0)[0])

                // Store transformation
                transforms.add(Transform(dx, dy, da));
            }

            frameGray.copyTo(prevGray)
        }

        videoInput.release()

        return Pair(
                transforms.toList(),
                VideoProps(
                        videoWidth,
                        videoHeight,
                        videoFrameRate,
                        videoRotation.toInt(),
                        frameCounter
                )
        )
    }

    private fun stabApply(inputPath: String, outputPath: String, transforms: List<Transform>, videoParams: VideoProps): Boolean {
        BusyDialog.show("Smooth movements")

        // Compute trajectory using cumulative sum of transformations
        val trajectory = transforms.cumsum()

        // Smooth trajectory using moving average filter
        val smoothedTrajectory = trajectory.smooth(100)
        val transformsSmooth = mutableListOf<Transform>()

        for(i in trajectory.indices) {
            // Calculate difference in smoothed_trajectory and trajectory
            val diffX = smoothedTrajectory[i].x - trajectory[i].x
            val diffY = smoothedTrajectory[i].y - trajectory[i].y
            val diffA = smoothedTrajectory[i].a - trajectory[i].a

            // Calculate newer transformation array
            val dx = transforms[i].x + diffX
            val dy = transforms[i].y + diffY
            val da = transforms[i].a + diffA

            transformsSmooth.add(Transform(dx, dy, da))
        }

        val t = Mat(2, 3, CV_64F)
        val frameStabilized = Mat()

        val videoInput = openVideoCapture(inputPath)
        if (!videoInput.isOpened) throw FileNotFoundException()

        val videoOutput = VideoWriter(
                outputPath,
                VideoWriter.fourcc('M','J','P','G'),
                videoParams.frameRate.toDouble(),
                Size( videoParams.width.toDouble(), videoParams.height.toDouble() )
        )

        if (!videoOutput.isOpened) {
            videoInput.release()
            throw FileNotFoundException(outputPath)
        }

        var frameIndex = 0
        val frame = Mat()
        for (transform in transformsSmooth) {
            if (!videoInput.read(frame)) break

            frameIndex++
            BusyDialog.show("Stabilize frame $frameIndex / ${videoParams.frameCount}")

            // Extract transform from translation and rotation angle.
            transform.getTransform(t)

            // Apply affine wrapping to the given frame
            warpAffine(frame, frameStabilized, t, frame.size())

            // Scale image to remove black border artifact
            fixBorder(frameStabilized)

            videoOutput.write( frameStabilized )
        }

        videoOutput.release()
        videoInput.release()

        return true
    }

    private fun stabVideoAsync(videoUri: Uri) {
        var success = false
        val destFile = getOutputPath()

        BusyDialog.show("Prepare")

        try {
            //OpenCV expects a file path not an URI so copy it
            val tmpFile = copyUriToTempFile(videoUri) ?: throw FileNotFoundException()

            BusyDialog.show("Stabilize")
            val (transforms, videoProps) = stabAnalyze(tmpFile)

            if (transforms.isNotEmpty()) {
                success = stabApply(tmpFile, destFile, transforms, videoProps)
            }

            File(tmpFile).delete()
        } catch (e: Exception) {
        }

        runOnUiThread {
            BusyDialog.dismiss()

            videoUriStabilized = null
            if (success) {
                videoUriStabilized = File(destFile).toUri()

                val values = ContentValues()
                @Suppress("DEPRECATION")
                values.put(MediaStore.Images.Media.DATA, destFile)
                values.put(MediaStore.Images.Media.MIME_TYPE, "video/avi")
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            }

            binding.videoStabilized.setVideoURI(videoUriStabilized)
            updateView()
        }
    }

    private fun stabVideo() {
        videoUriStabilized = null
        val videoUriOriginal = this.videoUriOriginal ?: return

        videoStop()

        GlobalScope.launch(Dispatchers.Default) {
            stabVideoAsync(videoUriOriginal)
        }
    }

    private fun openVideo(videoUri: Uri) {
        videoUriOriginal = videoUri
        videoName = (DocumentFile.fromSingleUri(applicationContext, videoUri)?.name ?: "").split('.')[0]
        binding.videoOriginal.setVideoURI(videoUriOriginal)
        binding.videoStabilized.setVideoURI(null)
        stabVideo()
        updateView()
    }

    private fun updateView() {
        if (null == videoUriStabilized) {
            binding.layoutViewMode.visibility = View.GONE
            binding.layoutOriginal.visibility = View.VISIBLE
            binding.layoutStabilized.visibility = View.GONE
        } else {
            binding.layoutViewMode.visibility = View.VISIBLE

            when (binding.viewMode.selectedItemPosition) {
                VIEW_MODE_ORIGINAL -> {
                    binding.layoutOriginal.visibility = View.VISIBLE
                    binding.layoutStabilized.visibility = View.GONE
                }
                VIEW_MODE_STABILIZED -> {
                    binding.layoutOriginal.visibility = View.GONE
                    binding.layoutStabilized.visibility = View.VISIBLE
                }
                else -> {
                    binding.layoutPlayer.orientation = if (VIEW_MODE_SPLIT_VERTICAL == binding.viewMode.selectedItemPosition) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    binding.layoutOriginal.visibility = View.VISIBLE
                    binding.layoutStabilized.visibility = View.VISIBLE
                }
            }
        }

        val originalAvailable = null != videoUriOriginal
        binding.play.isEnabled = originalAvailable
        binding.pause.isEnabled = originalAvailable
        binding.stop.isEnabled = originalAvailable
    }
}
