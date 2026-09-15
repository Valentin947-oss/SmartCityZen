package mk.smartcityzen.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/** Shown briefly on app launch with the Smart CityZen logo/banner, then hands off
 *  to MainActivity. This activity — not MainActivity — is now the launcher entry
 *  point (see AndroidManifest.xml). */
class SplashActivity : AppCompatActivity() {

    private val splashDurationMs = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, splashDurationMs)
    }
}
