package mk.smartcityzen.app.model

/**
 * Semi-static accessibility profile of an entrance — filled in once by whoever
 * checks it, unlike AccessibilityReport which is an ad-hoc pothole/obstacle pin.
 */
data class InstitutionAccessibility(
    val hasStepFreeEntrance: Boolean = false,
    val entranceType: String = EntranceType.UNKNOWN.name,
    val rampWidthOk: Boolean = false,
    val hasAccessibleToilet: Boolean = false,
    val hasAccessibleParking: Boolean = false,
    val hasElevator: Boolean = false,
    val hasTactilePaving: Boolean = false,
    val hasAudioSignage: Boolean = false,
    val notes: String = ""
)

data class Institution(
    val id: String = "",
    val name: String = "",
    val category: String = InstitutionCategory.OTHER.name,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String = "",
    val accessibility: InstitutionAccessibility = InstitutionAccessibility(),
    val photoUrl: String? = null,
    val status: String = "pending", // pending | verified | disputed
    val verificationCount: Int = 0,
    val addedBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null
)
