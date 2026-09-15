package mk.smartcityzen.app.util

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One-shot current GPS location — used when a citizen reports an obstacle so the
 * pin lands where they're actually standing, not just where they tap the map.
 * Caller must have already checked ACCESS_FINE_LOCATION permission.
 */
@SuppressLint("MissingPermission")
suspend fun getCurrentGpsLocation(context: Context): Pair<Double, Double>? {
    val client = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { continuation ->
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    continuation.resume(location.latitude to location.longitude)
                } else {
                    continuation.resume(null)
                }
            }
            .addOnFailureListener { e -> continuation.resumeWithException(e) }
    }
}
