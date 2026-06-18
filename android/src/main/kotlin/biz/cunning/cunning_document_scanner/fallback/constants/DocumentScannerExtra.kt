package biz.cunning.cunning_document_scanner.fallback.constants

/**
 * This class contains constants meant to be used as intent extras
 */
class DocumentScannerExtra {
    companion object {
        const val EXTRA_CROPPED_IMAGE_QUALITY = "croppedImageQuality"
        const val EXTRA_MAX_NUM_DOCUMENTS = "maxNumDocuments"

        // Framing-guide rectangle for the in-app camera: width:height aspect of
        // the guide box, and the margin (0..0.4 fraction of the shorter side)
        // kept around it.
        const val EXTRA_GUIDE_ASPECT = "guideAspect"
        const val EXTRA_GUIDE_INSET = "guideInset"
    }
}