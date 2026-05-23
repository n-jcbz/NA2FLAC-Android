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
        return File(context.applicationInfo.nativeLibraryDir)
    }

    fun getVgmstream(binDir: File) = File(binDir, "libvgmstream.so")
    fun getFfmpeg(binDir: File) = File(binDir, "libffmpeg.so")
    fun getFfprobe(binDir: File) = File(binDir, "libffprobe.so")
}
