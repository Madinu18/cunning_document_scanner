package biz.cunning.cunning_document_scanner.fallback

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import biz.cunning.cunning_document_scanner.R
import biz.cunning.cunning_document_scanner.fallback.constants.DefaultSetting
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import biz.cunning.cunning_document_scanner.fallback.ui.GuideOverlayView
import biz.cunning.cunning_document_scanner.fallback.utils.DocumentDetector
import biz.cunning.cunning_document_scanner.fallback.utils.FileUtil
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * In-app camera screen with a framing guide and the torch forced on. Live
 * OpenCV edge detection outlines the document; once it's aligned with the guide
 * and held steady the shot is taken automatically (a countdown ring shows
 * progress), and the shutter can also be tapped manually while aligned. The
 * captured photo path plus the document corners (normalized 0..1, ordered
 * TL,TR,BR,BL) are returned so the crop step starts from the real edges.
 *
 * The preview/overlay are sized to the capture aspect (4:3) and centered, so
 * the normalized corners map onto the saved photo.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PHOTO_PATH = "photoFilePath"
        // 8 floats: x,y for TL,TR,BR,BL (normalized 0..1, upright).
        const val RESULT_QUAD = "quad"

        // How long the document must stay aligned before auto-capture (ms).
        private const val AUTO_CAPTURE_MS = 600f
        // Max normalized corner distance from the guide to count as aligned.
        private const val ALIGN_TOLERANCE = 0.13f
    }

    private lateinit var previewView: PreviewView
    private lateinit var guideOverlay: GuideOverlayView
    private lateinit var shutterButton: ImageButton
    private lateinit var hintView: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var galleryButton: ImageButton

    private var imageCapture: ImageCapture? = null
    private var capturing = false

    // Optional in-app camera controls (off by default → behave as before).
    private var galleryImportAllowed = false
    private var flashControlAllowed = false

    // Camera handle + torch state, kept so the flash toggle can flip the torch.
    private var camera: Camera? = null
    private var torchOn = true // overridden by intent extra in onCreate

    // Picks an image from the device gallery when the gallery button is shown.
    private lateinit var galleryLauncher: ActivityResultLauncher<String>

    private var detectionEnabled = false
    // Most recent detected document quad (aligned or not); used to preset the
    // crop on a manual tap. Null when nothing is currently detected.
    private var lastQuad: FloatArray? = null
    private var alignedSince = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var analysisExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview_view)
        guideOverlay = findViewById(R.id.guide_overlay)
        shutterButton = findViewById(R.id.shutter_button)
        hintView = findViewById(R.id.guide_hint)
        flashButton = findViewById(R.id.flash_button)
        galleryButton = findViewById(R.id.gallery_button)

        galleryImportAllowed =
            intent.getBooleanExtra(DocumentScannerExtra.EXTRA_GALLERY_IMPORT_ALLOWED, false)
        flashControlAllowed =
            intent.getBooleanExtra(DocumentScannerExtra.EXTRA_FLASH_CONTROL_ALLOWED, false)
        torchOn =
            intent.getBooleanExtra(DocumentScannerExtra.EXTRA_DEFAULT_FLASH_ON, true)

        // Gallery picker: must be registered before the activity is RESUMED.
        galleryLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) onGalleryImagePicked(uri)
            }
        if (galleryImportAllowed) {
            galleryButton.visibility = View.VISIBLE
            galleryButton.setOnClickListener { galleryLauncher.launch("image/*") }
        }

        // Flash toggle button is configured in startCamera() once we know whether
        // the device actually has a flash unit. Hidden unless explicitly allowed.
        flashButton.setOnClickListener { toggleTorch() }

        analysisExecutor = Executors.newSingleThreadExecutor()
        detectionEnabled = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            false
        }

        guideOverlay.guideAspect =
            intent.getDoubleExtra(DocumentScannerExtra.EXTRA_GUIDE_ASPECT, DefaultSetting.GUIDE_ASPECT)
        guideOverlay.guideInset =
            intent.getDoubleExtra(DocumentScannerExtra.EXTRA_GUIDE_INSET, DefaultSetting.GUIDE_INSET)

        findViewById<ImageButton>(R.id.close_button).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        shutterButton.setOnClickListener { takePhoto() }

        // The shutter is always tappable (manual capture works even when the
        // document isn't aligned — you can fix the corners in the crop step).
        // Auto-capture still only fires when aligned & steady.
        shutterButton.isEnabled = true
        shutterButton.alpha = 1f
        hintView.text =
            if (detectionEnabled) "Posisikan dokumen di dalam bingkai" else "Ketuk untuk ambil foto"

        val root = findViewById<View>(android.R.id.content)
        root.post {
            sizePreviewToAspect()
            startCamera()
        }
    }

    /**
     * Resize the preview + overlay to the 4:3 capture aspect (portrait 3:4),
     * centered, so the guide maps 1:1 onto the captured photo.
     */
    private fun sizePreviewToAspect() {
        val vw = previewView.width
        val vh = previewView.height
        if (vw == 0 || vh == 0) return

        val targetAspect = 3f / 4f
        var w = vw
        var h = (w / targetAspect).toInt()
        if (h > vh) {
            h = vh
            w = (h * targetAspect).toInt()
        }

        for (v in listOf<View>(previewView, guideOverlay)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = w
            lp.height = h
            lp.gravity = android.view.Gravity.CENTER
            v.layoutParams = lp
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview, imageCapture!!)
            if (detectionEnabled) {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyzeFrame(image) }
                useCases.add(analysis)
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray()
                )
                this.camera = camera
                val hasFlash = camera.cameraInfo.hasFlashUnit()
                // Torch starts on for scanning; the optional flash button can
                // turn it off.
                if (hasFlash) {
                    camera.cameraControl.enableTorch(torchOn)
                }
                // Show the flash toggle only when allowed and the device can.
                if (flashControlAllowed && hasFlash) {
                    flashButton.visibility = View.VISIBLE
                    updateFlashIcon()
                } else {
                    flashButton.visibility = View.GONE
                }
            } catch (e: Exception) {
                finishWithError("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis executor; detects the document, then updates UI. */
    private fun analyzeFrame(image: ImageProxy) {
        if (capturing) {
            image.close()
            return
        }
        val quad = try {
            DocumentDetector.detect(image)
        } catch (e: Throwable) {
            null
        } finally {
            image.close()
        }
        mainHandler.post { onDetection(quad) }
    }

    private fun onDetection(quad: FloatArray?) {
        if (capturing) return

        val aligned = quad != null && isAligned(quad)
        guideOverlay.setDetection(quad, aligned)
        // Keep the latest detection (aligned or not) so a manual tap can still
        // start the crop from the detected edges.
        lastQuad = quad

        val now = SystemClock.elapsedRealtime()
        if (aligned) {
            if (alignedSince == 0L) alignedSince = now
            val progress = (now - alignedSince) / AUTO_CAPTURE_MS
            guideOverlay.setCaptureProgress(progress)
            hintView.text = "Posisi OK — menahan…"
            if (progress >= 1f) takePhoto()
        } else {
            alignedSince = 0L
            guideOverlay.setCaptureProgress(0f)
            hintView.text =
                if (quad == null) "Posisikan dokumen di dalam bingkai" else "Sejajarkan dengan bingkai"
        }
    }

    /** True when all 4 detected corners sit close to the guide's corners. */
    private fun isAligned(quad: FloatArray): Boolean {
        val vw = guideOverlay.width.toFloat()
        val vh = guideOverlay.height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        val gr = guideOverlay.guideRect
        // Guide corners, normalized, ordered TL,TR,BR,BL.
        val gx = floatArrayOf(gr.left / vw, gr.right / vw, gr.right / vw, gr.left / vw)
        val gy = floatArrayOf(gr.top / vh, gr.top / vh, gr.bottom / vh, gr.bottom / vh)
        for (i in 0 until 4) {
            if (abs(quad[i * 2] - gx[i]) > ALIGN_TOLERANCE) return false
            if (abs(quad[i * 2 + 1] - gy[i]) > ALIGN_TOLERANCE) return false
        }
        return true
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        guideOverlay.setCaptureProgress(0f)

        // Crop corners: the detected document edges when we have them, otherwise
        // the framing guide rectangle.
        val quad = lastQuad ?: guideRectAsQuad()

        val photoFile = FileUtil().createImageFile(this, 0)
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    val data = Intent().apply {
                        putExtra(RESULT_PHOTO_PATH, photoFile.absolutePath)
                        putExtra(RESULT_QUAD, quad)
                    }
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    finishWithError("Capture failed: ${exception.message}")
                }
            }
        )
    }

    /** The guide rectangle as a normalized TL,TR,BR,BL quad (8 floats). */
    private fun guideRectAsQuad(): FloatArray {
        val vw = guideOverlay.width.toFloat().coerceAtLeast(1f)
        val vh = guideOverlay.height.toFloat().coerceAtLeast(1f)
        val gr = guideOverlay.guideRect
        val l = gr.left / vw
        val t = gr.top / vh
        val r = gr.right / vw
        val b = gr.bottom / vh
        return floatArrayOf(l, t, r, t, r, b, l, b)
    }

    /** Flip the torch on/off (flash toggle button) and refresh its icon. */
    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        flashButton.setImageResource(
            if (torchOn) R.drawable.ic_flash_on_24 else R.drawable.ic_flash_off_24
        )
    }

    /**
     * Copy the gallery-picked image into a temp file and return it like a
     * capture. No edge detection is run on imported images — the crop is preset
     * to the full image and the user adjusts the corners in the crop step.
     */
    private fun onGalleryImagePicked(uri: Uri) {
        // Stop auto-capture racing while we hand off the picked image.
        capturing = true
        try {
            val file: File = FileUtil().createImageFile(this, 0)
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                capturing = false
                finishWithError("Unable to open selected image")
                return
            }
            input.use { stream ->
                FileOutputStream(file).use { output -> stream.copyTo(output) }
            }
            val quad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            val data = Intent().apply {
                putExtra(RESULT_PHOTO_PATH, file.absolutePath)
                putExtra(RESULT_QUAD, quad)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        } catch (e: Exception) {
            capturing = false
            finishWithError("Unable to import image: ${e.message}")
        }
    }

    private fun finishWithError(message: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra("error", message))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
    }
}
