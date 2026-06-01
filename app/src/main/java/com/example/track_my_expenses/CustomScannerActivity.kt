package com.example.track_my_expenses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class CustomScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var torchBtn: ImageView
    private var torchEnabled = false

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // Guard: prevents the analyzer (which fires 30x/sec) from triggering twice
    private var hasScanned = false

    // Amount the user typed in the expense dialog before opening the scanner.
    // Will be injected into the UPI URI if the QR itself has no amount.
    private var userEnteredAmount: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_scanner)

        previewView = findViewById(R.id.previewView)
        torchBtn    = findViewById(R.id.btnTorch)
        torchBtn.setImageResource(R.drawable.ic_flash_off)

        // Receive the amount the user already typed in the dialog (may be null/blank)
        userEnteredAmount = intent.getStringExtra("user_amount")

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            ActivityCompat.requestPermissions(this, permissions, 101)
        }

        torchBtn.setOnClickListener {
            torchEnabled = !torchEnabled
            cameraControl?.enableTorch(torchEnabled)
            torchBtn.setImageResource(if (torchEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) }
            }
            val camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
            )
            cameraControl = camera.cameraControl
            cameraInfo    = camera.cameraInfo
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (hasScanned) { imageProxy.close(); return }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty() && !hasScanned) {
                    val value = barcodes[0].rawValue
                    if (value != null && value.startsWith("upi://")) {
                        hasScanned = true
                        handleUpiQr(value)
                    }
                }
            }
            .addOnFailureListener { Log.e("Scanner", "Scan error: ${it.message}") }
            .addOnCompleteListener { imageProxy.close() }
    }

    // ── Core logic ───────────────────────────────────────────────────────────────

    private fun handleUpiQr(rawValue: String) {
        val originalUri   = Uri.parse(rawValue)
        val qrAmount      = originalUri.getQueryParameter("am")
        val qrHasAmount   = !qrAmount.isNullOrBlank() && (qrAmount.toDoubleOrNull() ?: 0.0) > 0.0

        // ── Amount resolution ─────────────────────────────────────────────────
        // Priority:
        //   1. If QR already has a fixed amount  → use it as-is (merchant fixed price)
        //   2. Else if user typed an amount       → inject it into the URI
        //   3. Else                               → send with no amount (GPay will ask)
        //
        // When an amount is present in the final URI, GPay skips focusing the
        // amount field entirely → no keyboard opens on the payment screen.

        val resolvedAmount: String? = when {
            qrHasAmount                                          -> qrAmount   // case 1
            !userEnteredAmount.isNullOrBlank() &&
                    (userEnteredAmount!!.toDoubleOrNull() ?: 0.0) > 0.0 -> userEnteredAmount // case 2
            else                                                  -> null      // case 3
        }

        // ── Build the final payment URI ───────────────────────────────────────
        val uriBuilder = originalUri.buildUpon().clearQuery()
        for (param in originalUri.queryParameterNames) {
            when (param) {
                "am"  -> { /* handled below */ }
                else  -> uriBuilder.appendQueryParameter(param, originalUri.getQueryParameter(param))
            }
        }
        resolvedAmount?.let { uriBuilder.appendQueryParameter("am", it) }
        val finalUri = uriBuilder.build()

        // ── Build result for TripDetailsActivity ──────────────────────────────
        // We always send back the RAW scanned value so the dialog can autofill
        // the payee name (pn) and use the resolved amount for the amount field.
        // We also send resolvedAmount separately so the dialog can show it even
        // if the QR had no amount and we injected the user's own value.
        val resultIntent = Intent().apply {
            putExtra("upi_uri", rawValue)                          // raw QR data for pn/other fields
            putExtra("resolved_amount", resolvedAmount ?: "")      // final amount used (for dialog autofill)
            putExtra("qr_had_amount", qrHasAmount)                 // lets dialog know whether to trust QR amount
        }
        setResult(Activity.RESULT_OK, resultIntent)

        // ── Launch payment app ────────────────────────────────────────────────
        // FLAG_ACTIVITY_NEW_DOCUMENT + MULTIPLE_TASK = completely isolated fresh
        // task every scan, so a stale mid-payment GPay session never blocks the next scan.
        val paymentIntent = Intent(Intent.ACTION_VIEW, finalUri).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK     or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        }
        val chooser = Intent.createChooser(paymentIntent, "Complete payment with:").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No UPI app found on this device", Toast.LENGTH_SHORT).show()
            hasScanned = false
            return
        }

        finish()
    }

    // ── Gallery fallback ─────────────────────────────────────────────────────────

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                BarcodeScanning.getClient().process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val value = barcodes[0].rawValue
                            setResult(RESULT_OK, Intent().apply {
                                putExtra("scanned_barcode", value)
                            })
                            finish()
                        } else {
                            Toast.makeText(this, "No QR code found in the image.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Failed to process image.", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error loading image from gallery.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // User returned from GPay mid-payment — unlock scanner for a fresh scan
        hasScanned = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}