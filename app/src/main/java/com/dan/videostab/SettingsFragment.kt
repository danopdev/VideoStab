package com.dan.videostab

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dan.videostab.databinding.SettingsFragmentBinding


class SettingsFragment(activity: MainActivity ) : AppFragment(activity) {

    companion object {
        fun show(activity: MainActivity ) {
            activity.pushView("Settings", SettingsFragment( activity ))
        }
    }

    private lateinit var binding: SettingsFragmentBinding

    private fun updateView() {
        binding.switchEncode265.text = if (binding.switchEncode265.isChecked) "Encoder H265/HEVC" else "Encoder H264 (legacy)"
        binding.switchKeepAudio.text = if (binding.switchKeepAudio.isChecked) "Keep audio" else "Don't keep audio"
    }

    override fun onBack(homeButton: Boolean) {
        settings.encodeH265 = binding.switchEncode265.isChecked
        settings.keepAudio = binding.switchKeepAudio.isChecked
        settings.saveProperties()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = SettingsFragmentBinding.inflate( inflater )

        binding.switchEncode265.isChecked = settings.encodeH265
        binding.switchKeepAudio.isChecked = settings.keepAudio

        binding.switchEncode265.setOnCheckedChangeListener { _, _ -> updateView() }
        binding.switchKeepAudio.setOnCheckedChangeListener { _, _ -> updateView() }

        updateView()

        return binding.root
    }
}