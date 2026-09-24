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
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.warehouse.stockscanner.scan.AimSelector
import com.warehouse.stockscanner.scan.ScanConsensus
import com.warehouse.stockscanner.scan.toScanCandidate
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
        private const val SCAN_BUZZ_MS = 50L
        // Full HD instead of 720p: a 13-digit EAN held at arm's length is only
        // a few hundred pixels wide at 720p, too few for its thinnest bars to
        // survive motion blur. ML Kit recommends >=1920 px for 1D codes.
        private val ANALYSIS_SIZE = Size(1920, 1080)
        // ML Kit's auto-zoom may zoom in on a code too small to decode; capped
        // so the frame never zooms so far that the worker loses their aim.
        private const val MAX_AUTO_ZOOM = 4f
    }

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var tvLocationBadge: TextView
    private lateinit var btnTorch: Button
    private lateinit var cameraExecutor: ExecutorService
    private val handled = AtomicBoolean(false)
    private val consensus = ScanConsensus()
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

        tvHint.text = if (mode == MODE_LOCATION) "כוון את ה-QR של המדף למרכז המסגרת" else "כוון את ברקוד המוצר למרכז המסגרת"

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
            val optionsBuilder = if (mode == MODE_LOCATION) {
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
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
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                val boundCamera = camera!!
                val maxZoom = boundCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                if (maxZoom > 1f) {
                    optionsBuilder.setZoomSuggestionOptions(
                        ZoomSuggestionOptions.Builder { ratio ->
                            boundCamera.cameraControl.setZoomRatio(ratio)
                            true
                        }.setMaxSupportedZoomRatio(minOf(maxZoom, MAX_AUTO_ZOOM)).build()
                    )
                }
                val scanner = BarcodeScanning.getClient(optionsBuilder.build())
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(scanner, imageProxy)
                }
                enableTapToFocus(boundCamera)
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
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        // ML Kit reports bounding boxes in the upright image, so the frame's
        // center must be measured in upright dimensions too.
        val uprightWidth = if (rotation % 180 == 0) imageProxy.width else imageProxy.height
        val uprightHeight = if (rotation % 180 == 0) imageProxy.height else imageProxy.width
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (!handled.get()) {
                    val candidates = barcodes.mapNotNull { it.toScanCandidate() }
                    val aimed = AimSelector.pick(candidates, uprightWidth, uprightHeight)
                    val value = consensus.offer(aimed)
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

    /**
     * Continuous autofocus hunts on busy shelves (it may lock onto the bin in
     * front instead of the label behind it); a tap focuses and meters exactly
     * where the worker points, then CameraX returns to continuous AF after a
     * few seconds.
     */
    private fun enableTapToFocus(cam: Camera) {
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                cam.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).build()
                )
                view.performClick()
            }
            true
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vibrationAttributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vibrator.vibrate(VibrationEffect.createOneShot(SCAN_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE), vibrationAttributes)
            return
        }
        // Below API 33, AudioAttributes is the closest equivalent to
        // VibrationAttributes — shared between both legacy overloads so the
        // buzz duration and usage tag can't drift apart between them.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createOneShot(SCAN_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(SCAN_BUZZ_MS, audioAttributes)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
