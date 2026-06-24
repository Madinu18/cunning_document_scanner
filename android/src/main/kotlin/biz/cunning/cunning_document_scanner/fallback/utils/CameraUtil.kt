package biz.cunning.cunning_document_scanner.fallback.utils

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import biz.cunning.cunning_document_scanner.fallback.CameraActivity
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra

/**
 * Helper that launches the in-app [CameraActivity] (framing guide, forced
 * torch, live edge detection + auto/manual capture) and reports the captured
 * photo path plus the detected document corners (8 normalized values, ordered
 * TL,TR,BR,BL) used to preset the correctable crop.
 *
 * @param activity current activity
 * @param guideAspect width:height aspect of the framing guide
 * @param guideInset margin (fraction of shorter side) around the guide
 * @param galleryImportAllowed show a "pick from gallery" button in the camera
 * @param flashControlAllowed show a flash on/off toggle button in the camera
 * @param onPhotoCaptureSuccess called with the photo path and document corners
 * @param onCancelPhoto called when the user backs out without capturing
 */
class CameraUtil(
    private val activity: ComponentActivity,
    private val guideAspect: Double,
    private val guideInset: Double,
    private val galleryImportAllowed: Boolean = false,
    private val flashControlAllowed: Boolean = false,
    private val defaultFlashOn: Boolean = true,
    private val onPhotoCaptureSuccess: (photoFilePath: String, quad: FloatArray) -> Unit,
    private val onCancelPhoto: () -> Unit
) {
    private val startForResult = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val data = result.data
                val path = data?.getStringExtra(CameraActivity.RESULT_PHOTO_PATH)
                val quad = data?.getFloatArrayExtra(CameraActivity.RESULT_QUAD)
                if (path == null || quad == null || quad.size != 8) {
                    // includes the in-activity error path
                    onCancelPhoto()
                    return@registerForActivityResult
                }
                onPhotoCaptureSuccess(path, quad)
            }
            Activity.RESULT_CANCELED -> onCancelPhoto()
        }
    }

    /** Launch the in-app camera. [pageNumber] is unused now but kept for parity. */
    fun openCamera(pageNumber: Int) {
        val intent = Intent(activity, CameraActivity::class.java).apply {
            putExtra(DocumentScannerExtra.EXTRA_GUIDE_ASPECT, guideAspect)
            putExtra(DocumentScannerExtra.EXTRA_GUIDE_INSET, guideInset)
            putExtra(DocumentScannerExtra.EXTRA_GALLERY_IMPORT_ALLOWED, galleryImportAllowed)
            putExtra(DocumentScannerExtra.EXTRA_FLASH_CONTROL_ALLOWED, flashControlAllowed)
            putExtra(DocumentScannerExtra.EXTRA_DEFAULT_FLASH_ON, defaultFlashOn)
        }
        startForResult.launch(intent)
    }
}
