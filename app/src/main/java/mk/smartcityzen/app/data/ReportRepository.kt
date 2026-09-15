package mk.smartcityzen.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import mk.smartcityzen.app.model.AccessibilityReport
import mk.smartcityzen.app.model.ReportStatus

/**
 * Reads/writes crowdsourced accessibility reports in Firestore.
 * Collection layout: reports/{reportId}
 * Keeping it flat (no geohash sharding) is fine at city scale (Bitola);
 * add geohash bucketing under "reports_geo" if this ever needs to scale beyond one city.
 */
class ReportRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val collection = db.collection("reports")

    /** Live stream of all reports — the Map screen subscribes to this. */
    fun observeReports(): Flow<List<AccessibilityReport>> = callbackFlow {
        val registration: ListenerRegistration = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val reports = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(AccessibilityReport::class.java)?.copy(id = doc.id)
                }
                trySend(reports)
            }
        awaitClose { registration.remove() }
    }

    suspend fun submitReport(report: AccessibilityReport): String {
        val ref = collection.document()
        val withId = report.copy(id = ref.id)
        ref.set(withId).await()
        return ref.id
    }

    /** "ВЕРИФИЦИРАЈ ПРЕЧКА (+10 ПОЕНИ)" from the pitch deck — a neighbour confirms a report is real. */
    suspend fun verifyReport(reportId: String) {
        val ref = collection.document(reportId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = snap.getLong("verificationCount") ?: 0
            val newCount = current + 1
            tx.update(ref, "verificationCount", newCount)
            tx.update(ref, "lastVerifiedAt", System.currentTimeMillis())
            if (newCount >= 3) {
                tx.update(ref, "status", ReportStatus.CONFIRMED.name)
            }
        }.await()
    }
}
