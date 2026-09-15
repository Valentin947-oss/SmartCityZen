package mk.smartcityzen.app.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import mk.smartcityzen.app.R
import mk.smartcityzen.app.model.EntranceType
import mk.smartcityzen.app.model.Institution
import mk.smartcityzen.app.model.InstitutionAccessibility
import mk.smartcityzen.app.model.InstitutionCategory

object AddInstitutionDialog {

    fun show(
        context: Context,
        lat: Double,
        lng: Double,
        authorId: String,
        onSubmit: (Institution) -> Unit
    ) {
        val view = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_add_institution, null)

        val nameField = view.findViewById<EditText>(R.id.editInstitutionName)
        val categorySpinner = view.findViewById<Spinner>(R.id.spinnerCategory)
        val stepFreeCheck = view.findViewById<CheckBox>(R.id.checkStepFree)
        val entranceSpinner = view.findViewById<Spinner>(R.id.spinnerEntranceType)
        val toiletCheck = view.findViewById<CheckBox>(R.id.checkAccessibleToilet)
        val parkingCheck = view.findViewById<CheckBox>(R.id.checkAccessibleParking)
        val elevatorCheck = view.findViewById<CheckBox>(R.id.checkElevator)
        val tactileCheck = view.findViewById<CheckBox>(R.id.checkTactilePaving)
        val audioCheck = view.findViewById<CheckBox>(R.id.checkAudioSignage)
        val rampWidthCheck = view.findViewById<CheckBox>(R.id.checkRampWidthOk)
        val notesField = view.findViewById<EditText>(R.id.editInstitutionNotes)
        val locationLabel = view.findViewById<TextView>(R.id.institutionLocationLabel)

        categorySpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            InstitutionCategory.values().map { it.label }
        )
        entranceSpinner.adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            EntranceType.values().map { it.label }
        )
        locationLabel.text = "GPS: %.5f, %.5f".format(lat, lng)

        // Built with setPositiveButton set to a no-op, then the real click listener is
        // attached after show() via setOnShowListener — this is the only way to stop the
        // dialog auto-dismissing when validation fails (a plain setPositiveButton always
        // closes the dialog regardless of what the listener does, which is exactly how
        // "blank name -> silently nothing happens" bug happened before).
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton(context.getString(android.R.string.ok), null)
            .setNegativeButton(context.getString(android.R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    nameField.error = context.getString(R.string.institution_name_required)
                    Toast.makeText(context, R.string.institution_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val category = InstitutionCategory.values()[categorySpinner.selectedItemPosition]
                val entranceType = EntranceType.values()[entranceSpinner.selectedItemPosition]

                val institution = Institution(
                    name = name,
                    category = category.name,
                    lat = lat,
                    lng = lng,
                    accessibility = InstitutionAccessibility(
                        hasStepFreeEntrance = stepFreeCheck.isChecked,
                        entranceType = entranceType.name,
                        rampWidthOk = rampWidthCheck.isChecked,
                        hasAccessibleToilet = toiletCheck.isChecked,
                        hasAccessibleParking = parkingCheck.isChecked,
                        hasElevator = elevatorCheck.isChecked,
                        hasTactilePaving = tactileCheck.isChecked,
                        hasAudioSignage = audioCheck.isChecked,
                        notes = notesField.text?.toString().orEmpty()
                    ),
                    addedBy = authorId
                )
                onSubmit(institution)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
