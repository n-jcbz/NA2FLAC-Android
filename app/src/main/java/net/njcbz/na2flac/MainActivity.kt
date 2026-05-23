package net.njcbz.na2flac

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

// Colors matching the WPF app
private val BgDark       = Color(0xFF121212)
private val SurfaceDark  = Color(0xFF1E1E1E)
private val BorderGray   = Color(0xFF555555)
private val Teal         = Color(0xFF47A097)
private val TextWhite    = Color(0xFFFFFFFF)
private val TextMuted    = Color(0x80FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hide the status bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContent {
            NA2FLACApp()
        }
    }
}

@Composable
fun NA2FLACApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Extract binaries once on first launch
    LaunchedEffect(Unit) {
        BinaryManager.setup(context)
    }

    // State
    var inputUri    by remember { mutableStateOf<Uri?>(null) }
    var outputUri   by remember { mutableStateOf<Uri?>(null) }
    var statusText  by remember { mutableStateOf("Status: Idle (${BuildConfig.FLAVOR.uppercase()} - ${BuildConfig.MAX_THREADS} Threads)") }
    var progress    by remember { mutableFloatStateOf(0f) }
    var scanResult  by remember { mutableStateOf<Converter.ScanResult?>(null) }
    var isWorking   by remember { mutableStateOf(false) }

    // Sync progress from Service
    val serviceProgress by ConverterService.progressFlow.collectAsState()

    LaunchedEffect(serviceProgress) {
        if (serviceProgress.total > 0) {
            progress = serviceProgress.current.toFloat() / serviceProgress.total.toFloat()
            statusText = "Status: (${serviceProgress.current}/${serviceProgress.total}) ${serviceProgress.fileName}"
        }
        if (serviceProgress.isFinished && serviceProgress.result != null) {
            val result = serviceProgress.result!!
            val mins = result.elapsedMs / 60000
            val secs = (result.elapsedMs % 60000) / 1000
            statusText = "Status: Done! FLAC: ${result.converted}, " +
                    "WAV kept: ${result.wavKept}, " +
                    "Failed: ${result.failed}  –  ${mins}m ${secs}s"
            progress = 1f
            isWorking = false
        }
    }

    // Notification permission request
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Folder pickers
    val inputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            inputUri = uri
            // C# behavior: if output is not set, default it to input
            if (outputUri == null) {
                outputUri = uri
            }
            scanResult = null
            progress = 0f
            statusText = "Status: Folder selected. Tap Scan."
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            outputUri = uri
        }
    }

    // Root container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .safeDrawingPadding()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .size(45.dp)
                        .scale(2f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "NA2FLAC v2.0",
                    color = TextWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Input folder row
            FolderRow(
                label = "Input",
                uri = inputUri,
                onBrowse = { inputPicker.launch(null) }
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Scan / Convert buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                NA2Button(
                    text = "Scan",
                    modifier = Modifier.width(166.dp),
                    enabled = inputUri != null && !isWorking
                ) {
                    scope.launch {
                        isWorking = true
                        statusText = "Status: Scanning..."
                        try {
                            val result = Converter.scan(context, inputUri!!)
                            scanResult = result

                            if (result.files.isEmpty()) {
                                statusText = "Status: No supported files found."
                            } else {
                                val countMsg = result.countByExt.entries
                                    .sortedBy { it.key }
                                    .joinToString(" · ") { "${it.value} ${it.key.uppercase()}" }
                                statusText = "Status: $countMsg, ${result.files.size} total  –  " +
                                        "${Converter.formatSize(result.totalBytes)} → " +
                                        "~${Converter.formatSize(result.estimatedBytes)} after conversion"
                            }
                        } catch (e: Exception) {
                            statusText = "Status: Scan error: ${e.message}"
                        }
                        isWorking = false
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                NA2Button(
                    text = "Convert",
                    modifier = Modifier.width(166.dp),
                    enabled = scanResult != null && scanResult!!.files.isNotEmpty() && !isWorking
                ) {
                    if (inputUri != null) {
                        isWorking = true
                        progress = 0f
                        ConverterService.start(context, inputUri!!, outputUri ?: inputUri!!)
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Output folder row
            FolderRow(
                label = "Output",
                uri = outputUri,
                onBrowse = { outputPicker.launch(null) }
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Open Output button
            NA2Button(
                text = "Open Output Folder",
                modifier = Modifier.fillMaxWidth(),
                enabled = outputUri != null
            ) {
                // To open a specific SAF folder, we need to build a document URI from the tree URI
                val rootDocId = DocumentsContract.getTreeDocumentId(outputUri!!)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(outputUri, rootDocId)
                
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(docUri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    statusText = "Status: No file manager found to open folder."
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                    .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(Teal, RoundedCornerShape(12.dp))
                    )
                }
            }

            // Status box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(5.dp))
                    .border(1.dp, BorderGray, RoundedCornerShape(5.dp))
                    .padding(5.dp)
            ) {
                Text(
                    text = statusText,
                    color = TextWhite,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FolderRow(label: String, uri: Uri?, onBrowse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(SurfaceDark, RoundedCornerShape(20.dp))
                .border(1.dp, BorderGray, RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = uri?.lastPathSegment ?: "$label directory",
                color = if (uri != null) TextWhite else TextMuted,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        NA2Button(
            text = "Select $label",
            onClick = onBrowse,
            modifier = Modifier.width(120.dp)
        )
    }
}

@Composable
fun NA2Button(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) Teal else BorderGray,
        animationSpec = tween(durationMillis = 250),
        label = "buttonBorderColor"
    )

    val borderThickness by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 250),
        label = "buttonBorderThickness"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF333333),
            contentColor = TextWhite,
            disabledContainerColor = Color(0xFF222222),
            disabledContentColor = TextMuted
        ),
        border = androidx.compose.foundation.BorderStroke(borderThickness, borderColor)
    ) {
        Text(text = text, fontSize = 16.sp)
    }
}
