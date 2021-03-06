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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
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

        private fun fixBorder(frame: Mat) {
            val t = getRotationMatrix2D(Point(frame.cols() / 2.0, frame.rows() / 2.0), 0.0, 1.04)
            warpAffine(frame, frame, t, frame.size())
        }
    }

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var videoUriOriginal: Uri? = null
    private var videoName  = ""
    private var videoProps: VideoProps? = null
    private var videoTransforms: List<Transform>? = null

    private var menuSave: MenuItem? = null
    private var menuStabilize: MenuItem? = null

    private val tmpFolder: String
        get() = applicationContext.cacheDir.absolutePath
    private val tmpOutputVideo: String
        get() = "$tmpFolder/tmp_video.avi"
    private val tmpOutputVideoIntermediate: String
        get() = "$tmpFolder/tmp_video_intermediate.avi"
    private val tmpOutputVideoExists: Boolean
        get() = File(tmpOutputVideo).exists()
    private val tmpInputVideo: String
        get() = "$tmpFolder/input.video"

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
        TmpFiles(tmpFolder).delete()

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

        menuStabilize = menu?.findItem(R.id.menuStabilize)
        menuSave = menu?.findItem(R.id.menuSave)

        updateView()

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.menuOpen -> handleOpenVideo()
            R.id.menuStabilize -> handleStabilize()
            R.id.menuSave -> handleSave()
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

            /*
            val values = ContentValues()
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.DATA, outputPath)
            //values.put(MediaStore.Images.Media.MIME_TYPE, "video/mp4")
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
             */

            success = true
        } catch (e: Exception) {
        }

        runOnUiThread {
            Toast.makeText(applicationContext, if (success) "Saved: $outputPath" else "Failed !", Toast.LENGTH_LONG).show()
        }
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
            outputFilePath = SAVE_FOLDER + "/" + videoName + (if (0 == counter) "" else "_${String.format("%03d", counter)}") + ".mp4"
            if (!File(outputFilePath).exists()) break
            counter++
        }

        return outputFilePath
    }

    private fun copyUriToTempFile() {
        val videoUri = this.videoUriOriginal ?: throw FileNotFoundException()
        val inputStream = contentResolver.openInputStream(videoUri) ?: throw FileNotFoundException()
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


    private fun stabAnalyzeAsync() {
        try {
            videoProps = null
            videoTransforms = null

            //OpenCV expects a file path not an URI so copy it
            copyUriToTempFile()

            val videoInput = openVideoCapture(tmpInputVideo)
            if (!videoInput.isOpened) return

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

                cvtColor(frame, frameGray, COLOR_RGB2GRAY)

                if (!prevGray.empty()) {
                    // Detect features in previous frame
                    val prevPts = MatOfPoint()
                    goodFeaturesToTrack(prevGray, prevPts, 200, 0.01, 30.0)

                    // Calculate optical flow (i.e. track feature points)
                    val prevPts2f = MatOfPoint2f()
                    prevPts2f.fromList(prevPts.toList())
                    val framePts2f = MatOfPoint2f()
                    val status = MatOfByte()
                    val err = MatOfFloat()
                    calcOpticalFlowPyrLK(prevGray, frameGray, prevPts2f, framePts2f, status, err)

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

                    // Store transformation
                    transforms.add(Transform(dx, dy, da))
                }

                frameGray.copyTo(prevGray)
            }

            videoInput.release()

            videoProps = VideoProps(
                    videoWidth,
                    videoHeight,
                    videoFrameRate,
                    videoRotation.toInt(),
                    frameCounter
            )
            videoTransforms = transforms.toList()
        } catch (e: Exception) {
        }
    }

    private fun stabApplyAsync() {
        BusyDialog.show("Smooth movements")

        val transforms = videoTransforms ?: return
        val videoProps = this.videoProps ?: return

        TmpFiles(tmpFolder).delete("tmp_")

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

        val videoInput = openVideoCapture(tmpInputVideo)
        if (!videoInput.isOpened) throw FileNotFoundException()

        val videoOutput = VideoWriter(
                tmpOutputVideoIntermediate,
                VideoWriter.fourcc('M','J','P','G'),
                videoProps.frameRate.toDouble(),
                Size( videoProps.width.toDouble(), videoProps.height.toDouble() )
        )

        if (!videoOutput.isOpened) {
            videoInput.release()
            throw FileNotFoundException(tmpOutputVideoIntermediate)
        }

        var frameIndex = 0
        val frame = Mat()

        if (videoInput.read(frame)) {
            frameIndex++
            BusyDialog.show("Stabilize frame $frameIndex / ${videoProps.frameCount}")
            videoOutput.write(frame)

            for (transform in transformsSmooth) {
                if (!videoInput.read(frame)) break

                frameIndex++
                BusyDialog.show("Stabilize frame $frameIndex / ${videoProps.frameCount}")

                // Extract transform from translation and rotation angle.
                transform.getTransform(t)

                // Apply affine wrapping to the given frame
                warpAffine(frame, frameStabilized, t, frame.size())

                // Scale image to remove black border artifact
                fixBorder(frameStabilized)

                videoOutput.write(frameStabilized)
            }
        }

        videoOutput.release()
        videoInput.release()

        BusyDialog.show("Create video")

        val ffmpegParams = listOf(
                "-y",
                "-i $tmpOutputVideoIntermediate",
                "-vcodec mpeg4",
                "-q:v 2",
                tmpOutputVideo
        ).joinToString(" ")
        val session = FFmpegKit.execute(ffmpegParams)
        val output = session.output

        if (ReturnCode.isSuccess(session.returnCode)) {
            File(tmpOutputVideoIntermediate).delete()
        } else {
            TmpFiles(tmpFolder).delete("tmp_")
        }
    }

    private fun runAsync(initialMessage: String, asyncTask: () -> Unit) {
        videoStop()

        GlobalScope.launch(Dispatchers.Default) {
            BusyDialog.show(initialMessage)
            asyncTask()
            runOnUiThread() {
                updateView()
                BusyDialog.dismiss()
            }
        }
    }

    private fun openVideo(videoUri: Uri) {
        TmpFiles(tmpFolder).delete()
        videoProps = null
        videoTransforms = null
        videoUriOriginal = videoUri
        videoName = (DocumentFile.fromSingleUri(applicationContext, videoUri)?.name ?: "").split('.')[0]
        binding.videoOriginal.setVideoURI(videoUriOriginal)
        updateView()
        runAsync("Prepare") { stabAnalyzeAsync() }
    }

    private fun updateView() {
        if (!tmpOutputVideoExists) {
            binding.videoStabilized.setVideoURI(null)
            binding.layoutViewMode.visibility = View.GONE
            binding.layoutOriginal.visibility = View.VISIBLE
            binding.layoutStabilized.visibility = View.GONE
            menuSave?.isEnabled = false
            menuStabilize?.isEnabled = videoTransforms != null
        } else {
            binding.videoStabilized.setVideoURI(File(tmpOutputVideo).toUri())
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

            menuSave?.isEnabled = true
            menuStabilize?.isEnabled = true
        }

        val originalAvailable = null != videoUriOriginal
        binding.play.isEnabled = originalAvailable
        binding.pause.isEnabled = originalAvailable
        binding.stop.isEnabled = originalAvailable
    }
}
