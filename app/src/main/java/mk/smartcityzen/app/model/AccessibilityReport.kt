package mk.smartcityzen.app.model

/**
 * A single crowdsourced accessibility data point on the map.
 * Mirrors the "ДЕТАЛИ ЗА ПРИЈАВЕНА ПРЕЧКА" concept from the Smart CityZen pitch:
 * citizens report an obstacle or a positive feature (ramp), others verify it,
 * and the routing engine reads [severity] to penalize or reward nearby paths.
 */
data class AccessibilityReport(
    val id: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val type: ReportType = ReportType.OTHER,
    val comment: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val reporterMobilityType: String = MobilityType.WALKING.name,
    val verificationCount: Int = 0,
    val status: ReportStatus = ReportStatus.PENDING,
    val photoUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null
)

enum class ReportType(
    val label: String,          // Macedonian label shown in the UI
    val basePenalty: Double,    // multiplies the edge weight during routing; 1.0 = no effect
    val isPositive: Boolean
) {
    POTHOLE("Дупка на тротоар", basePenalty = 6.0, isPositive = false),
    NO_RAMP("Нема рампа", basePenalty = 8.0, isPositive = false),
    NARROW_PASSAGE("Тесен пролаз", basePenalty = 4.0, isPositive = false),
    BLOCKED_PATH("Целосно непроодно", basePenalty = 1000.0, isPositive = false),
    STEEP_SLOPE("Стрма падина", basePenalty = 3.0, isPositive = false),
    VERIFIED_RAMP("Верификувана рампа", basePenalty = 0.5, isPositive = true),
    ACCESSIBLE_ENTRANCE("Пристапен влез", basePenalty = 0.6, isPositive = true),
    ACCESSIBLE_PARKING("Инвалиден паркинг", basePenalty = 0.5, isPositive = true),
    OTHER("Друго", basePenalty = 1.5, isPositive = false);
}

enum class ReportStatus { PENDING, CONFIRMED, DISPUTED, RESOLVED }
