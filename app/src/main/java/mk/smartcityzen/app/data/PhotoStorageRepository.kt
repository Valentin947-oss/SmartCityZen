package mk.smartcityzen.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Uploads photos taken while reporting an obstacle to ImgBB (https://imgbb.com) — a
 * free image-hosting API that needs no billing account, unlike Firebase Storage which
 * started requiring a linked Blaze billing account for all projects from Feb 2026.
 *
 * Get a free API key at https://api.imgbb.com/ (just an email, no card) and put it
 * below. ImgBB returns a permanent direct URL to the uploaded JPEG.
 */
class PhotoStorageRepository(
    private val apiKey: String = "PASTE_YOUR_IMGBB_API_KEY_HERE",
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun uploadReportPhoto(context: Context, localUri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
            ?: throw IOException("Could not read photo file")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                "report_${System.currentTimeMillis()}.jpg",
                bytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://api.imgbb.com/1/upload?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Empty ImgBB response")
            if (!response.isSuccessful) {
                throw IOException("ImgBB upload failed: ${response.code} $body")
            }
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("data").get("url").asString
        }
    }
}
