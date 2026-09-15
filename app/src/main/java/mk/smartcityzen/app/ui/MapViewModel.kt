package mk.smartcityzen.app.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import mk.smartcityzen.app.data.InstitutionRepository
import mk.smartcityzen.app.data.PhotoStorageRepository
import mk.smartcityzen.app.data.ReportRepository
import mk.smartcityzen.app.model.AccessibilityReport
import mk.smartcityzen.app.model.EntranceType
import mk.smartcityzen.app.model.Institution
import mk.smartcityzen.app.model.InstitutionAccessibility
import mk.smartcityzen.app.model.InstitutionCategory
import mk.smartcityzen.app.model.ReportType
import mk.smartcityzen.app.routing.AccessibleRouteFinder
import mk.smartcityzen.app.routing.OsmGraphBuilder
import mk.smartcityzen.app.routing.PedestrianGraph
import mk.smartcityzen.app.routing.RouteResult

sealed class GraphState {
    object Loading : GraphState()
    data class Ready(val graph: PedestrianGraph) : GraphState()
    data class Error(val message: String) : GraphState()
}

class MapViewModel(
    private val repository: ReportRepository = ReportRepository(),
    private val institutionRepository: InstitutionRepository = InstitutionRepository(),
    private val photoRepository: PhotoStorageRepository = PhotoStorageRepository(),
    private val graphBuilder: OsmGraphBuilder = OsmGraphBuilder()
) : ViewModel() {

    private val _graphState = MutableStateFlow<GraphState>(GraphState.Loading)
    val graphState: StateFlow<GraphState> = _graphState

    private val _reports = MutableStateFlow<List<AccessibilityReport>>(emptyList())
    val reports: StateFlow<List<AccessibilityReport>> = _reports

    private val _institutions = MutableStateFlow<List<Institution>>(emptyList())
    val institutions: StateFlow<List<Institution>> = _institutions

    /** One-shot error messages for the UI to show as a toast — cleared after being read. */
    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent

    fun errorShown() { _errorEvent.value = null }

    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult

    private var routeFinder: AccessibleRouteFinder? = null

    /** Stable per-install identity — no login screen, just an anonymous Firebase uid
     *  so reports/verifications can be attributed without asking the citizen to sign up. */
    val currentUserId: String
        get() = FirebaseAuth.getInstance().currentUser?.uid ?: "anon"

    init {
        loadPedestrianNetwork()
        observeReports()
        observeInstitutions()
        ensureSignedIn()
    }

    private fun ensureSignedIn() {
        if (FirebaseAuth.getInstance().currentUser != null) return
        viewModelScope.launch {
            try {
                FirebaseAuth.getInstance().signInAnonymously().await()
                Log.d("MapViewModel", "Signed in anonymously: $currentUserId")
            } catch (e: Exception) {
                Log.e("MapViewModel", "Anonymous sign-in failed", e)
            }
        }
    }

    private fun loadPedestrianNetwork() {
        viewModelScope.launch {
            _graphState.value = GraphState.Loading
            try {
                val graph = graphBuilder.buildGraph()
                if (graph.isEmpty) {
                    Log.e("MapViewModel", "Graph came back empty")
                    _graphState.value = GraphState.Error("Празна мрежа — провери интернет или Overpass лимит.")
                } else {
                    routeFinder = AccessibleRouteFinder(graph)
                    _graphState.value = GraphState.Ready(graph)
                    Log.d("MapViewModel", "Graph ready with ${graph.nodes.size} nodes")
                }
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to load pedestrian network", e)
                _graphState.value = GraphState.Error(e.message ?: "Грешка при вчитување мрежа.")
            }
        }
    }

    private fun observeReports() {
        viewModelScope.launch {
            repository.observeReports().collect { list -> _reports.value = list }
        }
    }

    private fun observeInstitutions() {
        viewModelScope.launch {
            institutionRepository.observeInstitutions().collect { list -> _institutions.value = list }
        }
    }

    fun submitInstitution(institution: Institution) {
        viewModelScope.launch {
            try {
                institutionRepository.submitInstitution(institution)
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to submit institution", e)
                _errorEvent.value = "Не успеав да зачувам институција: ${e.message}"
            }
        }
    }

    fun verifyInstitution(institutionId: String) {
        viewModelScope.launch {
            try {
                institutionRepository.verifyInstitution(institutionId)
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to verify institution", e)
                _errorEvent.value = "Не успеав да верификувам: ${e.message}"
            }
        }
    }

    /**
     * Writes a handful of demo reports/institutions scattered around central Bitola so
     * there's something on the map to test with, instead of starting from a blank
     * Firestore database. Coordinates are approximate, invented for testing — NOT
     * verified real-world accessibility data. Wired to a long-press for now; remove
     * or gate behind a debug build flag before any real deployment.
     */
    fun seedDemoData() {
        val authorId = currentUserId
        viewModelScope.launch {
            val demoReports = listOf(
                AccessibilityReport(lat = 41.0295, lng = 21.3330, type = ReportType.POTHOLE,
                    comment = "Голема дупка на тротоарот", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0300, lng = 21.3360, type = ReportType.NO_RAMP,
                    comment = "Висок бордура, нема рампа", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0285, lng = 21.3320, type = ReportType.NARROW_PASSAGE,
                    comment = "Тесен пролаз меѓу продавниците", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0310, lng = 21.3355, type = ReportType.BLOCKED_PATH,
                    comment = "Целосно блокирано со градежен материјал", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0270, lng = 21.3340, type = ReportType.STEEP_SLOPE,
                    comment = "Многу стрма падина", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0293, lng = 21.3345, type = ReportType.VERIFIED_RAMP,
                    comment = "Убава нова рампа", authorId = authorId, authorName = "Демо", verificationCount = 3),
                AccessibilityReport(lat = 41.0301, lng = 21.3338, type = ReportType.ACCESSIBLE_ENTRANCE,
                    comment = "Пристапен влез", authorId = authorId, authorName = "Демо", verificationCount = 2),
                AccessibilityReport(lat = 41.0288, lng = 21.3365, type = ReportType.POTHOLE,
                    comment = "Дупка близу пазарот", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0275, lng = 21.3300, type = ReportType.NO_RAMP,
                    comment = "Нема рампа кај аптеката", authorId = authorId, authorName = "Демо"),
                AccessibilityReport(lat = 41.0305, lng = 21.3320, type = ReportType.OTHER,
                    comment = "Расипано осветлување навечер", authorId = authorId, authorName = "Демо")
            )
            var reportsOk = 0
            demoReports.forEach {
                try {
                    repository.submitReport(it)
                    reportsOk++
                } catch (e: Exception) {
                    Log.e("MapViewModel", "Seed report failed", e)
                }
            }

            val demoInstitutions = listOf(
                Institution(name = "Општина Битола", category = InstitutionCategory.GOVERNMENT.name,
                    lat = 41.0296, lng = 21.3346, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = true, entranceType = EntranceType.RAMP.name)),
                Institution(name = "Клинички Центар Битола", category = InstitutionCategory.HEALTH.name,
                    lat = 41.0250, lng = 21.3400, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = true, entranceType = EntranceType.LEVEL.name, hasAccessibleParking = true)),
                Institution(name = "Универзитетска библиотека", category = InstitutionCategory.CULTURE.name,
                    lat = 41.0310, lng = 21.3330, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = false, entranceType = EntranceType.STEPS_NO_RAMP.name)),
                Institution(name = "Аптека Св. Пантелејмон", category = InstitutionCategory.PHARMACY.name,
                    lat = 41.0288, lng = 21.3352, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = true, entranceType = EntranceType.RAMP.name)),
                Institution(name = "Комерцијална банка", category = InstitutionCategory.BANK.name,
                    lat = 41.0299, lng = 21.3341, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = true, entranceType = EntranceType.LEVEL.name)),
                Institution(name = "Плоштад Гоце Делчев", category = InstitutionCategory.CULTURE.name,
                    lat = 41.0280577, lng = 21.3356441, addedBy = authorId,
                    accessibility = InstitutionAccessibility(hasStepFreeEntrance = true, entranceType = EntranceType.LEVEL.name,
                        notes = "Отворен плоштад, рамна површина"))
            )
            var institutionsOk = 0
            demoInstitutions.forEach {
                try {
                    institutionRepository.submitInstitution(it)
                    institutionsOk++
                } catch (e: Exception) {
                    Log.e("MapViewModel", "Seed institution failed", e)
                }
            }

            Log.d("MapViewModel", "Seeded $reportsOk/${demoReports.size} reports and $institutionsOk/${demoInstitutions.size} institutions")
            if (reportsOk == 0 && institutionsOk == 0) {
                _errorEvent.value = "Ниту еден демо запис не се зачува — провери Firestore rules/интернет."
            } else {
                _errorEvent.value = "Зачувани $reportsOk пријави и $institutionsOk институции."
            }
        }
    }

    fun submitReport(report: AccessibilityReport) {
        viewModelScope.launch {
            try {
                repository.submitReport(report)
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to submit report", e)
                _errorEvent.value = "Не успеав да зачувам пријава: ${e.message}"
            }
        }
    }

    /** Uploads the photo (if any) to ImgBB first, then writes the report with its URL. */
    fun submitReportWithPhoto(context: android.content.Context, report: AccessibilityReport, photoUri: Uri?) {
        viewModelScope.launch {
            try {
                val photoUrl = try {
                    photoUri?.let { photoRepository.uploadReportPhoto(context, it) }
                } catch (e: Exception) {
                    Log.e("MapViewModel", "Photo upload failed, saving report without photo", e)
                    null
                }
                repository.submitReport(report.copy(photoUrl = photoUrl))
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to submit report", e)
                _errorEvent.value = "Не успеав да зачувам пријава: ${e.message}"
            }
        }
    }

    fun verifyReport(reportId: String) {
        viewModelScope.launch {
            try {
                repository.verifyReport(reportId)
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to verify report", e)
                _errorEvent.value = "Не успеав да верификувам: ${e.message}"
            }
        }
    }

    fun computeRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double) {
        val finder = routeFinder
        if (finder == null) {
            Log.e("MapViewModel", "computeRoute called but routeFinder is null (graph not ready)")
            return
        }
        viewModelScope.launch {
            val result = finder.findRoute(startLat, startLon, endLat, endLon, _reports.value)
            Log.d("MapViewModel", "findRoute result: ${result?.points?.size ?: "null"} points")
            _routeResult.value = result ?: RouteResult(emptyList(), 0.0, 0)
        }
    }
}
