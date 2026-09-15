package mk.smartcityzen.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import mk.smartcityzen.app.model.Institution

/**
 * Reads/writes semi-static institution accessibility profiles.
 * Collection layout: institutions/{institutionId}
 */
class InstitutionRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val collection = db.collection("institutions")

    fun observeInstitutions(): Flow<List<Institution>> = callbackFlow {
        val registration: ListenerRegistration = collection
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val institutions = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Institution::class.java)?.copy(id = doc.id)
                }
                trySend(institutions)
            }
        awaitClose { registration.remove() }
    }

    suspend fun submitInstitution(institution: Institution): String {
        val ref = collection.document()
        ref.set(institution.copy(id = ref.id)).await()
        return ref.id
    }

    suspend fun verifyInstitution(institutionId: String) {
        val ref = collection.document(institutionId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = snap.getLong("verificationCount") ?: 0
            val newCount = current + 1
            tx.update(ref, "verificationCount", newCount)
            tx.update(ref, "lastVerifiedAt", System.currentTimeMillis())
            if (newCount >= 3) {
                tx.update(ref, "status", "verified")
            }
        }.await()
    }
}
