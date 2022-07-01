package com.dan.videostab

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.dan.videostab.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader


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
            binding.videoOriginal.start()
            binding.videoStabilized.start()
        }

        binding.pause.setOnClickListener {
            binding.videoOriginal.pause()
            binding.videoStabilized.pause()
        }

        binding.stop.setOnClickListener {
            binding.videoOriginal.pause()
            binding.videoOriginal.seekTo(0)
            binding.videoStabilized.pause()
            binding.videoStabilized.seekTo(0)
        }

        binding.viewMode.setSelection( VIEW_MODE_SPLIT_HORIZONTAL )

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

    private fun openVideo(videoUri: Uri) {
        videoUriOriginal = videoUri
        videoUriStabilized = videoUri
        videoName = DocumentFile.fromSingleUri(applicationContext, videoUri)?.name ?: ""
        binding.videoOriginal.setVideoURI(videoUriOriginal)
        binding.videoStabilized.setVideoURI(videoUriStabilized)
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