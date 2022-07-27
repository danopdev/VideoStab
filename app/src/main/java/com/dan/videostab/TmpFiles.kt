package com.dan.videostab

import java.io.File

class TmpFiles(private val path: String) {
    fun delete( startsWidth: String = "" ) {
        File(path).listFiles()?.forEach { file ->
            if (file.isFile) {
                val deleteFile = startsWidth.isEmpty() ||file.name.startsWith(startsWidth)
                if (deleteFile) file.delete()
            }
        }
    }
}