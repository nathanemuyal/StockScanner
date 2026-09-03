package com.warehouse.stockscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic full-screen barcode/QR scanner. Supports EAN-13, EAN-8, UPC-A,
 * UPC-E, Code 128, Code 39, QR and Data Matrix. Location scans are restricted
 * to QR/Data Matrix only (see startCamera()) so a product's 1D barcode sitting
 * in the same frame as a shelf label can never be mistaken for the location.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LOCATION = "LOCATION"
        const val MODE_PRODUCT = "PRODUCT"
        const val EXTRA_VALUE = "value"
        const val EXTRA_CURRENT_LOCATION = "current_location"
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var tvLocationBadge: TextView
    private lateinit var cameraExecutor: ExecutorService
    private val handled = AtomicBoolean(false)
    private var mode: String = MODE_PRODUCT

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "יש צורך בהרשאת מצלמה כדי לסרוק", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PRODUCT
        val currentLocation = intent.getStringExtra(EXTRA_CURRENT_LOCATION)
        previewView = findViewById(R.id.previewView)
        tvHint = findViewById(R.id.tvHint)
        tvLocationBadge = findViewById(R.id.tvLocationBadge)
        findViewById<Button>(R.id.btnCancelScan).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        tvHint.text = if (mode == MODE_LOCATION) "סרוק את ה-QR של המדף" else "סרוק ברקוד מוצר"

        // While scanning products, keep reminding the user which shelf they're on.
        if (mode == MODE_PRODUCT && !currentLocation.isNullOrBlank()) {
            tvLocationBadge.text = "📍 מיקום נוכחי: $currentLocation"
            tvLocationBadge.visibility = android.view.View.VISIBLE
        } else {
            tvLocationBadge.visibility = android.view.View.GONE
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // A location scan must only ever recognize the shelf label's 2D code
            // — if 1D barcode formats were also enabled here, a product barcode
            // sitting in the same frame (very common on a shelf, confirmed from
            // real warehouse photos) could get decoded first and be mistaken for
            // the location. Real shelf labels turned out to use Data Matrix, not
            // QR (Data Matrix and QR are easy to mix up — both are small square
            // 2D codes) — both are accepted here so either kind of label works,
            // while 1D barcode formats stay excluded. Product scans still accept
            // QR/Data Matrix too, since some products may be labeled with one.
            val options = if (mode == MODE_LOCATION) {
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                    .build()
            } else {
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39
                    )
                    .build()
            }
            val scanner = BarcodeScanning.getClient(options)

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(scanner, imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "שגיאה בפתיחת המצלמה: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(scanner: BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (!handled.get()) {
                    val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (value != null && handled.compareAndSet(false, true)) {
                        onScanned(value)
                    }
                }
            }
            .addOnFailureListener {
                // Ignore single-frame failures; the next frame will retry.
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun onScanned(value: String) {
        runOnUiThread {
            val result = Intent().putExtra(EXTRA_VALUE, value)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
