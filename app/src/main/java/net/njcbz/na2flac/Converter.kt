package net.njcbz.na2flac

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // LOGGER
    // -------------------------------------------------------------------------

    private val logLock = Any()

    private fun logToFile(file: File?, message: String) {
        if (file == null) return
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            synchronized(logLock) {
                file.appendText("[$time] $message\n")
            }
        } catch (_: Exception) {}
        android.util.Log.d("NA2FLAC", message)
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

        val logFile = File(context.cacheDir, "conversion_log.txt")
        logFile.writeText("NA2FLAC Conversion Log - ${Date()}\n")
        logFile.appendText("Binary directory: ${binDir.absolutePath}\n")
        logFile.appendText("----------------------------------------------------------\n\n")

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
                            logToFile(logFile, "Starting processing: ${file.name} (Relative: ${file.relativePath})")

                            val workDir = File(cacheDir, file.relativePath.replace(':', '/')).also { it.mkdirs() }
                            val srcFile = File(workDir, file.name)
                            
                            try {
                                context.contentResolver.openInputStream(file.uri)?.use { input ->
                                    srcFile.outputStream().use { output -> input.copyTo(output) }
                                }
                            } catch (e: Exception) {
                                logToFile(logFile, "FAILED to read source file ${file.name}: ${e.message}")
                                failedCount.incrementAndGet()
                                completedCount.incrementAndGet()
                                return@launch
                            }

                            val baseName = file.name.substringBeforeLast('.')
                            val wavFile = File(workDir, "$baseName.wav")

                            // vgmstream: decode to WAV
                            runProcess(vgm.absolutePath, listOf(srcFile.absolutePath, "-o", wavFile.absolutePath), logFile)

                            if (!wavFile.exists()) {
                                logToFile(logFile, "FAILED: vgmstream did not produce WAV for ${file.name}")
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
                                    logToFile(logFile, "Found matching _r file for ${file.name}, merging to stereo.")
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
                                        ), logFile
                                    )
                                    if (flacFile.exists() && flacFile.length() > 0 && writeToOutput(context, flacFile, outputRootUri, convertedFolderUri, file.relativePath, "$mergedBase.flac")) {
                                        logToFile(logFile, "SUCCESS: Merged and saved $mergedBase.flac")
                                        wavFile.delete()
                                        rightWav.delete()
                                        flacFile.delete()
                                        convertedCount.incrementAndGet()
                                        merged = true
                                    } else {
                                        logToFile(logFile, "ERROR: Merged FLAC creation/write failed for $mergedBase")
                                        flacFile.delete()
                                    }
                                }
                            }

                            if (!merged) {
                                // ffprobe check
                                val channels = try {
                                    val ffprobeOut = runProcessCapture(
                                        ffprobe.absolutePath, listOf(
                                            "-v", "error", "-select_streams", "a:0",
                                            "-show_entries", "stream=channels",
                                            "-of", "default=noprint_wrappers=1:nokey=1",
                                            wavFile.absolutePath
                                        ), logFile
                                    )
                                    val c = ffprobeOut.trim().toIntOrNull() ?: 0
                                    logToFile(logFile, "Channel count for ${file.name}: $c")
                                    c
                                } catch (e: Exception) { 
                                    logToFile(logFile, "Channel check failed for ${file.name}: ${e.message}")
                                    0 
                                }

                                val flacFile = File(workDir, "$baseName.flac")
                                
                                // If channel count is valid and within FLAC limits, try converting
                                if (channels in 1..8) {
                                    runProcess(
                                        ffmpeg.absolutePath, listOf(
                                            "-y", "-i", wavFile.absolutePath, "-c:a", "flac", flacFile.absolutePath
                                        ), logFile
                                    )
                                } else {
                                    logToFile(logFile, "Skipping FLAC (channels: $channels), falling back to WAV.")
                                }

                                if (flacFile.exists() && flacFile.length() > 0 && writeToOutput(context, flacFile, outputRootUri, convertedFolderUri, file.relativePath, "$baseName.flac")) {
                                    logToFile(logFile, "SUCCESS: Converted to ${baseName}.flac")
                                    convertedCount.incrementAndGet()
                                } else {
                                    logToFile(logFile, "Keeping as WAV (FLAC failed or skipped).")
                                    if (writeToOutput(context, wavFile, outputRootUri, convertedFolderUri, file.relativePath, "$baseName.wav")) {
                                        logToFile(logFile, "SUCCESS: Saved ${baseName}.wav")
                                        wavKeptCount.incrementAndGet()
                                    } else {
                                        logToFile(logFile, "FAILED: Could not write output file for ${file.name}")
                                        failedCount.incrementAndGet()
                                    }
                                }
                                flacFile.delete()
                                wavFile.delete()
                            }

                            srcFile.delete()
                            completedCount.incrementAndGet()
                        }
                }
            }
        }

        cacheDir.deleteRecursively()
        val result = ConvertResult(convertedCount.get(), wavKeptCount.get(), failedCount.get(), System.currentTimeMillis() - startMs)
        
        logToFile(logFile, "\n----------------------------------------------------------")
        logToFile(logFile, "CONVERSION SUMMARY:")
        logToFile(logFile, "Total Files: $total")
        logToFile(logFile, "Converted to FLAC: ${result.converted}")
        logToFile(logFile, "Kept as WAV: ${result.wavKept}")
        logToFile(logFile, "Failed: ${result.failed}")
        logToFile(logFile, "Elapsed Time: ${result.elapsedMs / 1000}s")
        logToFile(logFile, "----------------------------------------------------------")

        writeToOutput(context, logFile, outputRootUri, convertedFolderUri, "", "conversion_log.txt")
        logFile.delete()
        
        result
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
    ): Boolean {
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
        val mime = when {
            fileName.endsWith(".flac") -> "audio/flac"
            fileName.endsWith(".wav")  -> "audio/x-wav"
            fileName.endsWith(".txt")  -> "text/plain"
            else -> "application/octet-stream"
        }
        val newUri = try {
            DocumentsContract.createDocument(
                context.contentResolver, currentDirUri, mime, fileName
            )
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error creating document: $fileName", e)
            null
        } ?: return false

        return try {
            context.contentResolver.openOutputStream(newUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("NA2FLAC", "Error writing to output stream: $fileName", e)
            false
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

    private fun runProcess(exe: String, args: List<String>, logFile: File? = null) {
        val cmd = mutableListOf(exe) + args
        logToFile(logFile, "Running: ${cmd.joinToString(" ")}")
        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                if (exitCode == 159) {
                    logToFile(logFile, "CRITICAL ERROR: Binary '$exe' triggered a Seccomp violation (SIGSYS 159).")
                } else {
                    logToFile(logFile, "Process failed with exit code $exitCode: $exe")
                }
                logToFile(logFile, "Output: $output")
            } else {
                logToFile(logFile, "Process success: $exe. Output: ${output.take(150).replace('\n', ' ')}...")
            }
        } catch (e: Exception) {
            logToFile(logFile, "Error running process: $exe - ${e.message}")
        }
    }

    private fun runProcessCapture(exe: String, args: List<String>, logFile: File? = null): String {
        val cmd = mutableListOf(exe) + args
        logToFile(logFile, "Running (Capture): ${cmd.joinToString(" ")}")
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                if (exitCode == 159) {
                    logToFile(logFile, "CRITICAL ERROR: Binary '$exe' triggered a Seccomp violation (SIGSYS 159).")
                } else {
                    logToFile(logFile, "Process failed with exit code $exitCode: $exe\nOutput: $output")
                }
            } else {
                logToFile(logFile, "Process success: $exe. Output: ${output.trim()}")
            }
            output
        } catch (e: Exception) {
            logToFile(logFile, "Error in runProcessCapture: $exe - ${e.message}")
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