package com.anythingllm.importer.ui.ftpqr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * v1.5 FTP 扫码连接:相机扫描 PC 端 ftp_server.py 输出的二维码,
 * 解码 JSON 后通过 RESULT_OK + EXTRA_RESULT 返回文本,由设置页解析回填。
 */
class ScanFtpQrActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RESULT = "qr_text"
    }

    private val decodeExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setContent {
                PermissionGate(
                    onGranted = { /* 权限授予后重组自然进入扫码 */ },
                    onDenied = { finish() },
                    onBack = { finish() },
                )
            }
            return
        }
        setContent { ScanUi(onBack = { finish() }) }
    }

    override fun onDestroy() {
        decodeExecutor.shutdown()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PermissionGate(onGranted: () -> Unit, onDenied: () -> Unit, onBack: () -> Unit) {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        ) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted = ok
            if (!ok) onDenied()
        }
        if (!granted) {
            androidx.compose.runtime.LaunchedEffect(Unit) { launcher.launch(Manifest.permission.CAMERA) }
        } else {
            onGranted()
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("扫码连接 FTP") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("需要相机权限才能扫描二维码", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScanUi(onBack: () -> Unit) {
        val context = LocalContext.current
        var scanHint by remember { mutableStateOf("对准 PC 终端中的二维码") }
        var decoded by remember { mutableStateOf(false) }
        var lastDecodeMs by remember { mutableLongStateOf(0L) }

        fun onDecoded(text: String) {
            if (decoded) return
            decoded = true
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
                finish()
            }
        }

        DisposableEffect(Unit) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(decodeExecutor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastDecodeMs < 800) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    lastDecodeMs = now
                    decodeFrame(proxy)?.let { text ->
                        runOnUiThread {
                            scanHint = "已识别,正在连接…"
                            onDecoded(text)
                        }
                    }
                    proxy.close()
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this@ScanFtpQrActivity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }
            providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
            onDispose {
                runCatching {
                    val p = if (providerFuture.isDone) providerFuture.get() else null
                    p?.unbindAll()
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("扫码连接 FTP") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 扫描框:白色细线边框示意
                        Box(
                            Modifier
                                .padding(horizontal = 40.dp)
                                .fillMaxWidth()
                                .height(180.dp)
                                .border(2.dp, Color.White),
                        )
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            tint = Color.White,
                        )
                        Text(
                            " $scanHint",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    /** 从 YUV_420_888 帧解码二维码;无结果返回 null */
    private fun decodeFrame(proxy: ImageProxy): String? {
        val image = proxy.image ?: return null
        val plane = image.planes.getOrNull(0) ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return try {
            val source = PlanarYUVLuminanceSource(
                data, image.width, image.height,
                0, 0, image.width, image.height, false,
            )
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
                put(DecodeHintType.TRY_HARDER, true)
            }
            val result = MultiFormatReader().apply { setHints(hints) }
                .decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            result.text
        } catch (e: Exception) {
            null
        }
    }
}
