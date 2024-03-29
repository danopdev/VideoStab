package com.dan.videostab

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.dan.videostab.databinding.BusyDialogBinding

class BusyDialog( private var message: String, private var progress: Int, private var total: Int): DialogFragment() {

    companion object {
        private const val FRAGMENT_TAG = "busy"
        private var currentDialog: BusyDialog? = null
        private lateinit var activity: MainActivity

        fun create(activity_: MainActivity) {
            activity = activity_
        }

        private fun runSafe( callback: ()->Unit ) {
            if (Looper.getMainLooper().isCurrentThread) {
                callback()
            } else {
                activity.runOnUiThread {
                    callback()
                }
            }
        }

        fun show(message: String, progress: Int = -1, total: Int = -1) {
            runSafe {
                if (null == currentDialog) {
                    val dialog = BusyDialog(message, progress, total)
                    dialog.isCancelable = false
                    dialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
                    currentDialog = dialog
                } else {
                    currentDialog?.update(message, progress, total)
                }
            }
        }

        fun dismiss() {
            runSafe {
                currentDialog?.dismiss()
                currentDialog = null
            }
        }

        fun showCancel() {
            runSafe {
                currentDialog?.showCancel()
            }
        }

        fun isCanceled(): Boolean {
            return currentDialog?._isCanceled ?: false
        }
    }

    private var _binding: BusyDialogBinding? = null
    private var _isCanceled = false
    private var _showCancel = false

    fun showCancel() {
        _showCancel = true
        _binding?.let {
            it.buttonCancel.visibility = View.VISIBLE
        }
    }

    fun update(message: String, progress: Int = -1, total: Int = -1) {
        this.message = message
        this.progress = progress
        this.total = total
        update()
    }

    private fun update() {
        val infinite = progress < 0 || total <= 0 || progress > total
        val title = if (progress < 0) message else "$message ($progress)"
        _binding?.let {
            it.textBusyMessage.text = title
            if (infinite) {
                it.progressBar.isIndeterminate = true
            } else {
                it.progressBar.progress = 0
                it.progressBar.max = total
                it.progressBar.progress = progress
                it.progressBar.isIndeterminate = false
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = BusyDialogBinding.inflate( inflater )
        binding.textBusyMessage.text = message
        this._binding = binding

        binding.buttonCancel.setOnClickListener {
            _isCanceled = true
            binding.buttonCancel.isEnabled = false
        }

        if (_showCancel) {
            binding.buttonCancel.visibility = View.VISIBLE
        }

        update()
        return binding.root
    }
}
