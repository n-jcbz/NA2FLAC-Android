package net.njcbz.na2flac

import android.content.Context
import java.io.File

object BinaryManager {

    /**
     * Returns the binary location from the native library directory.
     * On Android 10+, this is the only reliable way to execute custom binaries
     * because it bypasses the W^X (Write or Execute) security restriction.
     */
    fun setup(context: Context): File {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        
        // Log available binaries for debugging
        val bins = libDir.listFiles()?.joinToString { it.name } ?: "none"
        android.util.Log.d("NA2FLAC", "Binary directory: ${libDir.absolutePath}")
        android.util.Log.d("NA2FLAC", "Available binaries: $bins")
        
        return libDir
    }

    fun getVgmstream(binDir: File) = File(binDir, "libvgmstream.so")
    fun getFfmpeg(binDir: File) = File(binDir, "libffmpeg.so")
    fun getFfprobe(binDir: File) = File(binDir, "libffprobe.so")
}
