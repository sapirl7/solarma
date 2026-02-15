package app.solarma.wakeproof

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QR code scanner for Wake Proof challenge.
 * Uses CameraX + ML Kit for barcode scanning.
 */
@Singleton
class QrScanner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "Solarma.QrScanner"
        }

        private val _scanResult = MutableStateFlow<QrScanResult>(QrScanResult.Idle)
        val scanResult: StateFlow<QrScanResult> = _scanResult.asStateFlow()

        private var expectedCode: String? = null
        private var cameraExecutor: ExecutorService? = null
        private var imageAnalyzer: ImageAnalysis? = null

        /**
         * Start waiting for QR code scan.
         * @param code Expected QR code to validate against
         */
        fun startScanning(code: String) {
            expectedCode = code
            _scanResult.value = QrScanResult.Waiting
            Log.d(TAG, "Started QR scanning for code: $code")
        }

        /**
         * Create image analyzer for camera preview.
         */
        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun createAnalyzer(): ImageAnalysis.Analyzer {
            val scanner = BarcodeScanning.getClient()

            return ImageAnalysis.Analyzer { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null && expectedCode != null) {
                    val image =
                        InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees,
                        )

                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val rawValue = barcode.rawValue
                                if (rawValue != null) {
                                    Log.d(TAG, "QR code scanned: $rawValue")

                                    if (rawValue == expectedCode) {
                                        _scanResult.value = QrScanResult.Success
                                        Log.i(TAG, "QR code validated!")
                                    } else {
                                        _scanResult.value = QrScanResult.WrongCode(rawValue)
                                        Log.w(TAG, "Wrong QR code scanned")
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "QR scan failed", e)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }
        }

        fun stopScanning() {
            expectedCode = null
            _scanResult.value = QrScanResult.Idle
            cameraExecutor?.shutdown()
            cameraExecutor = null
        }
    }

/**
 * Result of QR scan.
 */
sealed class QrScanResult {
    object Idle : QrScanResult()

    object Waiting : QrScanResult()

    object Success : QrScanResult()

    data class WrongCode(val scanned: String) : QrScanResult()

    data class Error(val message: String) : QrScanResult()
}
