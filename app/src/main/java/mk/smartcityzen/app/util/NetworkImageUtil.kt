package mk.smartcityzen.app.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val imageDownloadClient = OkHttpClient()

/** Downloads an image (e.g. an ImgBB photo URL stored on a report) and decodes it
 *  downsampled so a full-resolution JPEG can't blow up memory in a detail dialog. */
suspend fun downloadBitmap(url: String, reqWidth: Int = 800, reqHeight: Int = 800): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            imageDownloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                decodeSampledBitmap(bytes, reqWidth, reqHeight)
            }
        } catch (e: Exception) {
            null
        }
    }
