package net.njcbz.na2flac

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

// Mirrors the C# conversion logic
object Converter {

    val SUPPORTED_EXTENSIONS = setOf(
        "ast", "brstm", "bcstm", "bfstm", "bfwav", "bwav", "swav", "strm",
        "lopus", "idsp", "hps", "dsp", "adx", "mp3", "ogg", "custom"
    )

    data class ScannedFile(
        val uri: Uri,
        val name: String,          // filename with extension
        val ext: String,           // lowercase extension without dot
        val sizeBytes: Long,
        val relativePath: String   // relative to the picked root, for output structure
    )

    data class ScanResult(
        val files: List<ScannedFile>,
        val totalBytes: Long,
        val estimatedBytes: Long,
        val countByExt: Map<String, Int>
    )

    data class ConvertResult(
        val converted: Int,
        val wavKept: Int,
        val failed: Int,
        val elapsedMs: Long
    )

    // -------------------------------------------------------------------------
    // SCAN
    // -------------------------------------------------------------------------

    suspend fun scan(context: Context, rootUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val files = mutableListOf<ScannedFile>()
        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
        walkDocumentTree(context, rootUri, rootDocId, files)

        val totalBytes = files.sumOf { it.sizeBytes }
        val estimatedBytes = files.sumOf { f ->
            (f.sizeBytes * estimateFlacMultiplier(f.name, f.ext)).toLong()
        }
        val countByExt = files.groupingBy { it.ext }.eachCount()

        ScanResult(files, totalBytes, estimatedBytes, countByExt)
    }

    private fun walkDocumentTree(
        context: Context,
        rootUri: Uri,
        dirDocId: String,
        results: MutableList<ScannedFile>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            dirDocId
        )

        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null, null, null
        ) ?: return

        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(0)
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: continue
                val size = it.getLong(3)

                val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkDocumentTree(context, rootUri, docId, results)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_EXTENSIONS) {
                        // Build a relative path for preserving folder structure in output
                        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
                        val relative = docId.removePrefix(rootDocId).trimStart('/', ':')
                        val relativeDir = relative.substringBeforeLast('/', "")

                        results.add(ScannedFile(docUri, name, ext, size, relativeDir))
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // CONVERT
    // -------------------------------------------------------------------------

    suspend fun convert(
        context: Context,
        binDir: File,
        scanResult: ScanResult,
        outputRootUri: Uri,
        onProgress: suspend (current: Int, total: Int, fileName: String) -> Unit
    ): ConvertResult = withContext(Dispatchers.IO) {

        val vgm = BinaryManager.getVgmstream(binDir)
        val ffmpeg = BinaryManager.getFfmpeg(binDir)
        val ffprobe = BinaryManager.getFfprobe(binDir)

        val cacheDir = File(context.cacheDir, "na2flac_work")
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        // C# behavior: Create a "converted" subfolder in the output root
        val rootDocId = DocumentsContract.getTreeDocumentId(outputRootUri)
        val rootDirUri = DocumentsContract.buildDocumentUriUsingTree(outputRootUri, rootDocId)
        val convertedFolderUri = findOrCreateDir(context, outputRootUri, rootDirUri, "converted")

        var convertedCount = java.util.concurrent.atomic.AtomicInteger(0)
        var wavKeptCount = java.util.concurrent.atomic.AtomicInteger(0)
        var failedCount = java.util.concurrent.atomic.AtomicInteger(0)
        var completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        val startMs = System.currentTimeMillis()
        val total = scanResult.files.size

        // Determine thread count: use available processors, but cap it based on build flavor
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val threadCount = cpuCount.coerceAtMost(BuildConfig.MAX_THREADS)
        
        android.util.Log.d("NA2FLAC", "Starting parallel conversion with $threadCount threads (Mode: ${if(BuildConfig.MAX_THREADS > 4) "Turbo" else "Standard"})")

        val semaphore = Semaphore(threadCount)

        coroutineScope {
            scanResult.files.forEach { file ->
                launch {
                    semaphore.withPermit {
                        onProgress(completedCount.get() + 1, total, file.name)

                        val workDir = File(cacheDir, file.relativePath.replace(':', '/')).also { it.mkdirs() }
                        val srcFile = File(workDir, file.name)
                        
                        try {
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                srcFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        } catch (e: Exception) {
                            failedCount.incrementAndGet()
                            completedCount.incrementAndGet()
                            return@launch
                        }

                        val baseName = file.name.substringBeforeLast('.')
                        val wavFile = File(workDir, "$baseName.wav")

                        // vgmstream: decode to WAV
                        runProcess(vgm.absolutePath, listOf(srcFile.absolutePath, "-o", wavFile.absolutePath))

                        if (!wavFile.exists()) {
                            failedCount.incrementAndGet()
                            srcFile.delete()
                            completedCount.incrementAndGet()
                            return@launch
                        }

                        var merged = false
                        // Channel merge: _l + _r → stereo FLAC
                        if (baseName.endsWith("_l")) {
                            val rightBase = baseName.dropLast(2) + "_r"
                            val rightWav = File(workDir, "$rightBase.wav")
                            if (rightWav.exists()) {
                                val mergedBase = baseName.dropLast(2)
                                val flacFile = File(workDir, "$mergedBase.flac")
                                runProcess(
                                    ffmpeg.absolutePath, listOf(
                                        "-y",
                                        "-i", wavFile.absolutePath,
                                        "-i", rightWav.absolutePath,
                                        "-filter_complex", "[0:a][1:a]amerge=inputs=2[a]",
                                        "-map", "[a]",
                                        "-c:a", "flac",
                                        flacFile.absolutePath
                                    )
                                )
                                if (flacFile.exists()) {
                                    wavFile.delete()
                                    rightWav.delete()
                                    writeToOutput(context, flacFile, outputRootUri, convertedFolderUri, file.relativePath, "$mergedBase.flac")
                                    flacFile.delete()
                                    convertedCount.incrementAndGet()
                                    merged = true
                                } else {
                                    wavKeptCount.incrementAndGet()
                                    merged = true 
                                }
                            }
                        }

                        if (!merged) {
                            // ffprobe check with error handling for the known Seccomp issue
                            val channels = try {
                                val ffprobeOut = runProcessCapture(
                                    ffprobe.absolutePath, listOf(
                                        "-v", "error", "-select_streams", "a:0",
                                        "-show_entries", "stream=channels",
                                        "-of", "default=noprint_wrappers=1:nokey=1",
                                        wavFile.absolutePath
                                    )
                                )
                                ffprobeOut.trim().toIntOrNull() ?: 0
                            } catch (e: Exception) { 0 }

                            if (channels <= 8) {
                                val flacFile = File(workDir, "$baseName.flac")
                                runProcess(
                                    ffmpeg.absolutePath, listOf(
                                        "-y", "-i", wavFile.absolutePath, "-c:a", "flac", flacFile.absolutePath
                                    )
                                )
                                if (flacFile.exists()) {
                                    wavFile.delete()
                                    writeToOutput(context, flacFile, outputRootUri, convertedFolderUri, file.relativePath, "$baseName.flac")
                                    flacFile.delete()
                                    convertedCount.incrementAndGet()
                                } else {
                                    // If ffmpeg fails, keep WAV as fallback
                                    writeToOutput(context, wavFile, outputRootUri, convertedFolderUri, file.relativePath, "$baseName.wav")
                                    wavFile.delete()
                                    wavKeptCount.incrementAndGet()
                                }
                            } else {
                                writeToOutput(context, wavFile, outputRootUri, convertedFolderUri, file.relativePath, "$baseName.wav")
                                wavFile.delete()
                                wavKeptCount.incrementAndGet()
                            }
                        }

                        srcFile.delete()
                        completedCount.incrementAndGet()
                    }
                }
            }
        }

        cacheDir.deleteRecursively()
        ConvertResult(convertedCount.get(), wavKeptCount.get(), failedCount.get(), System.currentTimeMillis() - startMs)
    }

    // -------------------------------------------------------------------------
    // OUTPUT WRITING (SAF)
    // -------------------------------------------------------------------------

    private fun writeToOutput(
        context: Context,
        file: File,
        treeUri: Uri,
        convertedFolderUri: Uri,
        relativePath: String,
        fileName: String
    ) {
        var currentDirUri = convertedFolderUri

        val segments = relativePath.split('/', '\\', ':').filter { it.isNotBlank() }
        for (segment in segments) {
            currentDirUri = findOrCreateDir(context, treeUri, currentDirUri, segment)
        }

        // Check if file already exists to delete it
        val parentDocId = DocumentsContract.getDocumentId(currentDirUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocId
        )
        
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == fileName) {
                        val existingDocId = cursor.getString(0)
                        val existingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
                        DocumentsContract.deleteDocument(context.contentResolver, existingUri)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error deleting existing file", e)
        }

        // Create the output file
        val mime = if (fileName.endsWith(".flac")) "audio/flac" else "audio/x-wav"
        val newUri = try {
            DocumentsContract.createDocument(
                context.contentResolver, currentDirUri, mime, fileName
            )
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error creating document: $fileName", e)
            null
        } ?: return

        try {
            context.contentResolver.openOutputStream(newUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error writing to output stream: $fileName", e)
        }
    }

    private fun findOrCreateDir(context: Context, treeUri: Uri, parentDocUri: Uri, name: String): Uri {
        val parentDocId = try {
            DocumentsContract.getDocumentId(parentDocUri)
        } catch (e: Exception) {
            // Fallback: If parentDocUri is a tree URI, get tree ID
            DocumentsContract.getTreeDocumentId(parentDocUri)
        }
        
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2)
                    val displayName = cursor.getString(1)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR && displayName == name) {
                        val docId = cursor.getString(0)
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error querying children of $parentDocId", e)
        }

        // Doesn't exist, create it
        val newUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
            )
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error creating directory: $name", e)
            null
        } ?: return parentDocUri

        // Return a TREE-BACKED uri for the next level
        val newDocId = DocumentsContract.getDocumentId(newUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, newDocId)
    }

    // -------------------------------------------------------------------------
    // PROCESS HELPERS
    // -------------------------------------------------------------------------

    private fun runProcess(exe: String, args: List<String>) {
        val cmd = mutableListOf(exe) + args
        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                if (exitCode == 159) {
                    android.util.Log.e("NA2FLAC", "CRITICAL ERROR: Binary '$exe' triggered a Seccomp violation (SIGSYS 159).")
                    android.util.Log.e("NA2FLAC", "This means the binary tried to use a forbidden system call (likely 'close_range').")
                    android.util.Log.e("NA2FLAC", "This binary is NOT compatible with your device's Android version/security policy.")
                } else {
                    android.util.Log.e("NA2FLAC", "Process failed with exit code $exitCode: $exe")
                }
                android.util.Log.e("NA2FLAC", "Output: $output")
            } else {
                android.util.Log.d("NA2FLAC", "Process success: $exe")
            }
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error running process: $exe", e)
        }
    }

    private fun runProcessCapture(exe: String, args: List<String>): String {
        val cmd = mutableListOf(exe) + args
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                if (exitCode == 159) {
                    android.util.Log.e("NA2FLAC", "CRITICAL ERROR: Binary '$exe' triggered a Seccomp violation (SIGSYS 159).")
                } else {
                    android.util.Log.e("NA2FLAC", "Capture process failed ($exitCode): $exe\nOutput: $output")
                }
            }
            output
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error in runProcessCapture: $exe", e)
            ""
        }
    }

    // -------------------------------------------------------------------------
    // SIZE HELPERS (direct port from C#)
    // -------------------------------------------------------------------------

    fun formatSize(bytes: Long): String {
        var size = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var i = 0
        while (size >= 1024 && i < units.size - 1) {
            size /= 1024
            i++
        }
        return "%.2f %s".format(size, units[i])
    }

    private fun estimateFlacMultiplier(fileName: String, ext: String): Double {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith("_32.brstm")      -> 4.7
            lower.endsWith("_only32.brstm")  -> 1.0
            lower.endsWith("32_n.brstm")     -> 1.0
            lower.endsWith("32_f.brstm")     -> 1.0
            lower.endsWith(".ry.32.brstm")   -> 1.35
            lower.endsWith(".32.c4.brstm")   -> 1.3
            ext == "brstm"                   -> 5.3
            ext == "bcstm"                   -> 4.5
            ext == "bfstm"                   -> 4.5
            ext == "bwav"                    -> 2.3
            ext == "bfwav"                   -> 1.5
            ext == "dsp"                     -> 1.5
            ext == "hps"                     -> 2.3
            ext == "strm"                    -> 0.56
            ext == "swav"                    -> 2.3
            ext == "lopus"                   -> 5.0
            ext == "ast"                     -> 1.22
            ext == "idsp"                    -> 2.0
            ext == "adx"                     -> 4.23
            ext == "ogg" || ext == "mp3"     -> 2.5
            ext == "custom"                  -> 1.0
            else                             -> 2.5
        }
    }
}