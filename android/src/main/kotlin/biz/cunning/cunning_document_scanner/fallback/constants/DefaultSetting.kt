package biz.cunning.cunning_document_scanner.fallback.constants

/**
 * This class contains default document scanner options
 */
class DefaultSetting {
    companion object {
        const val CROPPED_IMAGE_QUALITY = 100
        const val MAX_NUM_DOCUMENTS = 24

        // A4-portrait-ish guide box with an 8% margin by default.
        const val GUIDE_ASPECT = 0.707 // 1/sqrt(2), portrait A4 (w:h)
        const val GUIDE_INSET = 0.08
    }
}