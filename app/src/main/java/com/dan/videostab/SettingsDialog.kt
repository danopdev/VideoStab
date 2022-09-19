package com.dan.videostab

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.dan.videostab.databinding.SettingsDialogBinding


class SettingsDialog(private val activity: MainActivity ) : DialogFragment() {

    companion object {
        private const val DIALOG_TAG = "SETTINGS_DIALOG"

        fun show(activity: MainActivity ) {
            with( SettingsDialog( activity ) ) {
                isCancelable = false
                show(activity.supportFragmentManager, DIALOG_TAG)
            }
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SettingsDialogBinding.inflate( inflater )

        binding.videoEncoder.setSelection(activity.settings.encoder)

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnOK.setOnClickListener {
            activity.settings.encoder = binding.videoEncoder.selectedItemPosition

            activity.settings.saveProperties()
            dismiss()
        }

        return binding.root
    }
}