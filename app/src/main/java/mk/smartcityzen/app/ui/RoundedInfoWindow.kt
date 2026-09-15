package mk.smartcityzen.app.ui

import android.widget.TextView
import mk.smartcityzen.app.R
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow

/** A simple rounded pill-shaped balloon instead of OSMDroid's default white speech
 *  bubble with a pointer tail — just shows the marker's title, dismissed on tap. */
class RoundedInfoWindow(mapView: MapView) : InfoWindow(R.layout.info_window_bubble, mapView) {

    override fun onOpen(item: Any?) {
        val marker = item as? Marker ?: return
        mView.findViewById<TextView>(R.id.bubbleTitle).text = marker.title
        mView.setOnClickListener { close() }
    }

    override fun onClose() {
        // nothing to clean up
    }
}
