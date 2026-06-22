package biz.cunning.cunning_document_scanner.fallback

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import biz.cunning.cunning_document_scanner.R
import biz.cunning.cunning_document_scanner.fallback.constants.DefaultSetting
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import biz.cunning.cunning_document_scanner.fallback.extensions.onClick
import biz.cunning.cunning_document_scanner.fallback.extensions.saveToFile
import biz.cunning.cunning_document_scanner.fallback.extensions.screenHeight
import biz.cunning.cunning_document_scanner.fallback.extensions.screenWidth
import biz.cunning.cunning_document_scanner.fallback.models.Document
import biz.cunning.cunning_document_scanner.fallback.models.Point
import biz.cunning.cunning_document_scanner.fallback.models.Quad
import biz.cunning.cunning_document_scanner.fallback.ui.ImageCropView
import biz.cunning.cunning_document_scanner.fallback.utils.CameraUtil
import biz.cunning.cunning_document_scanner.fallback.utils.FileUtil
import biz.cunning.cunning_document_scanner.fallback.utils.ImageUtil
import java.io.File
/**
 * This class contains the main document scanner code. It opens the camera, lets the user
 * take a photo of a document (homework paper, business card, etc.), detects document corners,
 * allows user to make adjustments to the detected corners, depending on options, and saves
 * the cropped document. It allows the user to do this for 1 or more documents.
 *
 * @constructor creates document scanner activity
 */
class DocumentScannerActivity : AppCompatActivity() {
    /**
     * @property maxNumDocuments maximum number of documents a user can scan at a time
     */
    private var maxNumDocuments = DefaultSetting.MAX_NUM_DOCUMENTS

    /**
     * @property croppedImageQuality the 0 - 100 quality of the cropped image
     */
    private var croppedImageQuality = DefaultSetting.CROPPED_IMAGE_QUALITY

    /**
     * @property document This is the current document. Initially it's null. Once we capture
     * the photo, and find the corners we update document.
     */
    private var document: Document? = null

    /**
     * @property documents a list of documents (original photo file path, original photo
     * dimensions and 4 corner points)
     */
    private val documents = mutableListOf<Document>()

    /**
     * @property guideAspect width:height aspect of the framing guide rectangle
     * @property guideInset margin (fraction of shorter side) around the guide
     */
    private var guideAspect = DefaultSetting.GUIDE_ASPECT
    private var guideInset = DefaultSetting.GUIDE_INSET

    /**
     * @property galleryImportAllowed show a "pick from gallery" button in camera
     * @property flashControlAllowed show a flash on/off toggle in camera
     */
    private var galleryImportAllowed = false
    private var flashControlAllowed = false

    /**
     * @property cameraUtil launches the in-app camera and reports the captured
     * photo path plus the detected document corners. Built in [onCreate] once
     * the guide options have been read from the intent extras.
     */
    private lateinit var cameraUtil: CameraUtil

    /**
     * Called when the user captures a photo. [quad] are the detected document
     * corners (8 normalized values, TL,TR,BR,BL) used to preset the crop, which
     * the user can then correct.
     */
    private fun onPhotoCaptured(originalPhotoPath: String, quad: FloatArray) {
        // if maxNumDocuments is reached, hide the new photo button
        if (documents.size == maxNumDocuments - 1) {
            val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
            newPhotoButton.isClickable = false
            newPhotoButton.visibility = View.INVISIBLE
        }

        val photo: Bitmap? = try {
            ImageUtil().getImageFromFilePath(originalPhotoPath)
        } catch (exception: Exception) {
            finishIntentWithError("Unable to get bitmap: ${exception.localizedMessage}")
            return
        }

        if (photo == null) {
            finishIntentWithError("Document bitmap is null.")
            return
        }

        // Preset the crop to the detected document corners (mapped onto the
        // photo). The user corrects it from here.
        val corners = quadCorners(quad, photo.width, photo.height)

        document = Document(originalPhotoPath, photo.width, photo.height, corners)

        // user is allowed to move corners to make corrections
        try {
            imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)
            imageView.setImage(photo)
            val cornersInImagePreviewCoordinates = corners
                .mapOriginalToPreviewImageCoordinates(
                    imageView.imagePreviewBounds,
                    imageView.imagePreviewBounds.height() / photo.height
                )
            imageView.setCropper(cornersInImagePreviewCoordinates)
        } catch (exception: Exception) {
            finishIntentWithError("unable get image preview ready: ${exception.message}")
            return
        }
    }

    /** Called when the user backs out of the camera without capturing. */
    private fun onPhotoCancelled() {
        // can't reach the crop view until at least 1 photo is taken
        if (documents.isEmpty()) {
            onClickCancel()
        }
    }

    /**
     * Builds the 4 crop corners from a normalized quad (8 values, TL,TR,BR,BL)
     * and the photo dimensions, clamped to the image bounds.
     */
    private fun quadCorners(quad: FloatArray, width: Int, height: Int): Quad {
        val w = width.toDouble()
        val h = height.toDouble()
        fun px(i: Int) = (quad[i * 2] * w).coerceIn(0.0, w)
        fun py(i: Int) = (quad[i * 2 + 1] * h).coerceIn(0.0, h)
        return Quad(
            Point(px(0), py(0)), // top-left
            Point(px(1), py(1)), // top-right
            Point(px(2), py(2)), // bottom-right
            Point(px(3), py(3))  // bottom-left
        )
    }

    /**
     * @property imageView container with original photo and cropper
     */
    private lateinit var imageView: ImageCropView

    /**
     * called when activity is created
     *
     * @param savedInstanceState persisted data that maintains state
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show cropper, accept crop button, add new document button, and
        // retake photo button. Since we open the camera in a few lines, the user
        // doesn't see this until they finish taking a photo
        setContentView(R.layout.activity_image_crop)
        imageView = findViewById(R.id.image_view)

        try {
            // validate maxNumDocuments option, and update default if user sets it
            var userSpecifiedMaxImages: Int? = null
            intent.extras?.get(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS)?.let {
                if (it.toString().toIntOrNull() == null) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS} must be a positive number"
                    )
                }
                userSpecifiedMaxImages = it as Int
                maxNumDocuments = userSpecifiedMaxImages as Int
            }

            // validate croppedImageQuality option, and update value if user sets it
            intent.extras?.get(DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY)?.let {
                if (it !is Int || it < 0 || it > 100) {
                    throw Exception(
                        "${DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY} must be a number " +
                                "between 0 and 100"
                    )
                }
                croppedImageQuality = it
            }
        } catch (exception: Exception) {
            finishIntentWithError(
                "invalid extra: ${exception.message}"
            )
            return
        }

        // read framing-guide options (used by the in-app camera and to preset the crop)
        intent.extras?.get(DocumentScannerExtra.EXTRA_GUIDE_ASPECT)?.let {
            (it as? Number)?.let { n -> guideAspect = n.toDouble() }
        }
        intent.extras?.get(DocumentScannerExtra.EXTRA_GUIDE_INSET)?.let {
            (it as? Number)?.let { n -> guideInset = n.toDouble() }
        }

        // optional in-app camera controls
        galleryImportAllowed =
            intent.getBooleanExtra(DocumentScannerExtra.EXTRA_GALLERY_IMPORT_ALLOWED, false)
        flashControlAllowed =
            intent.getBooleanExtra(DocumentScannerExtra.EXTRA_FLASH_CONTROL_ALLOWED, false)

        // build the camera launcher now that guide options are known
        cameraUtil = CameraUtil(
            this,
            guideAspect = guideAspect,
            guideInset = guideInset,
            galleryImportAllowed = galleryImportAllowed,
            flashControlAllowed = flashControlAllowed,
            onPhotoCaptureSuccess = { path, quad -> onPhotoCaptured(path, quad) },
            onCancelPhoto = { onPhotoCancelled() }
        )

        // set click event handlers for new document button, accept and crop document button,
        // and retake document photo button
        val newPhotoButton: ImageButton = findViewById(R.id.new_photo_button)
        val completeDocumentScanButton: ImageButton = findViewById(
            R.id.complete_document_scan_button
        )
        val retakePhotoButton: ImageButton = findViewById(R.id.retake_photo_button)

        newPhotoButton.onClick { onClickNew() }
        completeDocumentScanButton.onClick { onClickDone() }
        retakePhotoButton.onClick { onClickRetake() }

        // open camera, so user can snap document photo
        try {
            openCamera()
        } catch (exception: Exception) {
            finishIntentWithError(
                "error opening camera: ${exception.message}"
            )
        }
    }

    /**
     * Set document to null since we're capturing a new document, and open the camera. If the
     * user captures a photo successfully document gets updated.
     */
    private fun openCamera() {
        document = null
        cameraUtil.openCamera(documents.size)
    }

    /**
     * Once user accepts by pressing check button, or by pressing add new document button, add
     * original photo path and 4 document corners to documents list. If user isn't allowed to
     * adjust corners, call this automatically.
     */
    private fun addSelectedCornersAndOriginalPhotoPathToDocuments() {
        // only add document it's not null (the current document photo capture, and corner
        // detection are successful)
        document?.let { document ->
            // convert corners from image preview coordinates to original photo coordinates
            // (original image is probably bigger than the preview image)
            val cornersInOriginalImageCoordinates = imageView.corners
                .mapPreviewToOriginalImageCoordinates(
                    imageView.imagePreviewBounds,
                    imageView.imagePreviewBounds.height() / document.originalPhotoHeight
                )
            document.corners = cornersInOriginalImageCoordinates
            documents.add(document)
        }
    }

    /**
     * This gets called when a user presses the new document button. Store current photo path
     * with document corners. Then open the camera, so user can take a photo of the next
     * page or document
     */
    private fun onClickNew() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        openCamera()
    }

    /**
     * This gets called when a user presses the done button. Store current photo path with
     * document corners. Then crop document using corners, and return cropped image paths
     */
    private fun onClickDone() {
        addSelectedCornersAndOriginalPhotoPathToDocuments()
        cropDocumentAndFinishIntent()
    }

    /**
     * This gets called when a user presses the retake photo button. The user presses this in
     * case the original document photo isn't good, and they need to take it again.
     */
    private fun onClickRetake() {
        // we're going to retake the photo, so delete the one we just took
        document?.let { document -> File(document.originalPhotoFilePath).delete() }
        openCamera()
    }

    /**
     * This gets called when a user doesn't want to complete the document scan after starting.
     * For example a user can quit out of the camera before snapping a photo of the document.
     */
    private fun onClickCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * This crops original document photo, saves cropped document photo, deletes original
     * document photo, and returns cropped document photo file path. It repeats that for
     * all document photos.
     */
    private fun cropDocumentAndFinishIntent() {
        val croppedImageResults = arrayListOf<String>()
        for ((pageNumber, document) in documents.withIndex()) {
            // crop document photo by using corners
            val croppedImage: Bitmap? = try {
                ImageUtil().crop(
                    document.originalPhotoFilePath,
                    document.corners
                )
            } catch (exception: Exception) {
                finishIntentWithError("unable to crop image: ${exception.message}")
                return
            }

            if (croppedImage == null) {
                finishIntentWithError("Result of cropping is null")
                return
            }

            // delete original document photo
            File(document.originalPhotoFilePath).delete()

            // save cropped document photo
            try {
                val croppedImageFile = FileUtil().createImageFile(this, pageNumber)
                croppedImage.saveToFile(croppedImageFile, croppedImageQuality)
                croppedImageResults.add(Uri.fromFile(croppedImageFile).toString())
            } catch (exception: Exception) {
                finishIntentWithError(
                    "unable to save cropped image: ${exception.message}"
                )
            }
        }

        // return array of cropped document photo file paths
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("croppedImageResults", croppedImageResults)
        )
        finish()
    }

    /**
     * This ends the document scanner activity, and returns an error message that can be
     * used to debug error
     *
     * @param errorMessage an error message
     */
    private fun finishIntentWithError(errorMessage: String) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra("error", errorMessage)
        )
        finish()
    }
}