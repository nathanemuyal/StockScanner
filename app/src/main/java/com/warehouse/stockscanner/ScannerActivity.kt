package com.warehouse.stockscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
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
    private lateinit var btnTorch: Button
    private lateinit var cameraExecutor: ExecutorService
    private val handled = AtomicBoolean(false)
    private var mode: String = MODE_PRODUCT
    private var camera: Camera? = null

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
        btnTorch = findViewById(R.id.btnTorch)
        findViewById<Button>(R.id.btnCancelScan).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            cam.cameraControl.enableTorch(cam.cameraInfo.torchState.value != TorchState.ON)
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
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                // Not every device has a flash unit — only offer the toggle
                // when one actually exists.
                val cameraInfo = camera?.cameraInfo
                if (cameraInfo?.hasFlashUnit() == true) {
                    btnTorch.visibility = android.view.View.VISIBLE
                    // Drives the button's label from CameraX's own torch
                    // state instead of separate local state: a backgrounded
                    // ON_STOP (incoming call, screen lock, Home) makes
                    // CameraX turn the torch off and reset this LiveData on
                    // its own when the screen is rebound, so the label stays
                    // correct for free instead of going stale at "כבה פנס"
                    // while the torch is actually already off.
                    cameraInfo.torchState.observe(this) { state ->
                        btnTorch.text = if (state == TorchState.ON) "🔦 כבה פנס" else "🔦 הדלק פנס"
                    }
                } else {
                    btnTorch.visibility = android.view.View.GONE
                }
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
            vibrateOnScan()
            val result = Intent().putExtra(EXTRA_VALUE, value)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    /**
     * Short confirmation buzz so a worker who scans without staring at the
     * screen every time still gets immediate feedback that the scan landed.
     */
    private fun vibrateOnScan() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() != true) return
        // Tagged as touch feedback (not e.g. usage "notification") so the
        // system is less likely to silence it under Do Not Disturb or a
        // quiet sound profile. Not a guarantee either way: on API 33+ this
        // usage is also the one gated by the device's own "touch feedback"
        // toggle in Settings, so a worker who turned that off system-wide
        // won't feel this buzz — a deliberate respecting of that setting,
        // not a bug.
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val vibrationAttributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE), vibrationAttributes)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE), audioAttributes)
            }
            else -> {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(50, audioAttributes)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
