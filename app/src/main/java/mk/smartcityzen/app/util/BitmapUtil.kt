package mk.smartcityzen.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Camera photos come out at full sensor resolution (often 3000px+ wide). Loading
 * one straight into an ImageView via setImageURI() can throw OutOfMemoryError and
 * crash the whole app — this decodes a downsampled version sized for a preview.
 */
fun loadSampledBitmap(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }

        boundsOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqWidth, reqHeight)
        boundsOptions.inJustDecodeBounds = false

        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
    } catch (e: Exception) {
        null
    }
}

/** Same downsampling logic, but decoding from raw bytes — used for images fetched over
 *  the network (e.g. an ImgBB photo URL) rather than a local content Uri. */
fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        boundsOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqWidth, reqHeight)
        boundsOptions.inJustDecodeBounds = false

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    } catch (e: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
