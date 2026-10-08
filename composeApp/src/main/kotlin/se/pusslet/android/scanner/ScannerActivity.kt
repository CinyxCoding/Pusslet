package se.pusslet.android.scanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.jetbrains.compose.resources.stringResource
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_close
import se.pusslet.ui.resources.scanner_hint
import se.pusslet.ui.resources.scanner_unavailable
import se.pusslet.ui.theme.PussletTheme
import se.pusslet.ui.theme.Spacing
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera scanner for invite QR codes and grocery barcodes (EAN-8/13, UPC). Runs ML Kit on-device; no image ever
 * leaves the phone. Returns the raw value in [EXTRA_VALUE].
 */
class ScannerActivity : AppCompatActivity() {
    private val log = Logger.withTag("Scanner")
    private lateinit var analysisExecutor: ExecutorService
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                )
                .build(),
        )
    }
    private val delivered = AtomicBoolean(false)
    private var cameraFailed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()
        setContent {
            PussletTheme {
                ScannerScreen(cameraFailed, onClose = ::finish) { view -> startCamera(view) }
            }
        }
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: IllegalStateException) {
                log.w(e) { "Camera could not be bound" }
                cameraFailed = true
            } catch (e: IllegalArgumentException) {
                log.w(e) { "No back camera" }
                cameraFailed = true
            } catch (e: java.util.concurrent.ExecutionException) {
                log.w(e) { "Camera provider failed" }
                cameraFailed = true
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || delivered.get()) {
            proxy.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                if (value != null && delivered.compareAndSet(false, true)) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VALUE, value))
                    finish()
                }
            }
            .addOnFailureListener { e -> log.d { "Frame not decoded: ${e::class.simpleName}" } }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        analysisExecutor.shutdown()
    }

    companion object {
        const val EXTRA_VALUE = "value"
    }
}

@Composable
private fun ScannerScreen(cameraFailed: Boolean, onClose: () -> Unit, onPreview: (PreviewView) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {
        Box(Modifier.fillMaxSize()) {
            if (!cameraFailed) {
                AndroidView(
                    factory = { context -> PreviewView(context).also(onPreview) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                stringResource(if (cameraFailed) Res.string.scanner_unavailable else Res.string.scanner_hint),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(Spacing.xl),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(Spacing.sm)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.action_close),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
