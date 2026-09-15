package mk.smartcityzen.app.ui

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import mk.smartcityzen.app.R
import mk.smartcityzen.app.model.AccessibilityReport
import mk.smartcityzen.app.model.MobilityType
import mk.smartcityzen.app.model.ReportType
import mk.smartcityzen.app.util.loadSampledBitmap

/**
 * Result of the report form — lat/lng come from GPS (auto-captured before the
 * dialog opens, see MainActivity.startReportFlow), the photo is already on disk
 * locally; MainActivity uploads it to Storage and writes the Firestore doc.
 */
data class PendingReport(
    val type: ReportType,
    val comment: String,
    val mobilityType: MobilityType
)

object ReportObstacleDialog {

    fun show(
        context: Context,
        lat: Double,
        lng: Double,
        photoUri: Uri?,
        onSubmit: (PendingReport) -> Unit
    ) {
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_report_obstacle, null)

        val typeSpinner = view.findViewById<Spinner>(R.id.spinnerReportType)
        val mobilitySpinner = view.findViewById<Spinner>(R.id.spinnerMobilityType)
        val commentField = view.findViewById<EditText>(R.id.editComment)
        val imagePreview = view.findViewById<ImageView>(R.id.imagePreview)
        val locationLabel = view.findViewById<TextView>(R.id.locationLabel)

        typeSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            ReportType.values().map { it.label }
        )
        mobilitySpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            MobilityType.values().map { it.label }
        )

        locationLabel.text = "GPS: %.5f, %.5f".format(lat, lng)

        if (photoUri != null) {
            val bitmap = loadSampledBitmap(context, photoUri, reqWidth = 800, reqHeight = 800)
            if (bitmap != null) {
                imagePreview.setImageBitmap(bitmap)
                imagePreview.visibility = android.view.View.VISIBLE
            }
        }

        AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton(context.getString(android.R.string.ok)) { _, _ ->
                val report = PendingReport(
                    type = ReportType.values()[typeSpinner.selectedItemPosition],
                    comment = commentField.text?.toString().orEmpty(),
                    mobilityType = MobilityType.values()[mobilitySpinner.selectedItemPosition]
                )
                onSubmit(report)
            }
            .setNegativeButton(context.getString(android.R.string.cancel), null)
            .show()
    }
}
