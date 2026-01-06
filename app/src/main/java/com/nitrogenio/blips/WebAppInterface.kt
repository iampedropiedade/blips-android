import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import android.annotation.SuppressLint
import androidx.core.view.WindowCompat
import androidx.core.graphics.toColorInt

class WebAppInterface(
    private val activity: Activity,
    private val webView: WebView,
    private val fusedLocationClient: FusedLocationProviderClient,
) {
    @JavascriptInterface
    @SuppressLint("MissingPermission")
    fun requestNativeLocation() {
        android.util.Log.d("WebAppInterface", "requestNativeLocation called")

        val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            0
        )
            .setWaitForAccurateLocation(true)
            .setMaxUpdates(1)
            .build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val location = result.lastLocation
                android.util.Log.d("WebAppInterface", "location received: $location")

                if (location != null) {
                    val json = """
                    {
                      "lat": ${location.latitude},
                      "lng": ${location.longitude},
                      "accuracy": ${location.accuracy},
                      "timestamp": ${location.time}
                    }
                    """.trimIndent()

                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onNativeLocationUpdate($json)",
                            null
                        )
                    }
                }

                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            android.os.Looper.getMainLooper()
        )
    }

    @JavascriptInterface
    fun updateStatusBar(isDarkMode: Boolean, hexColor: String): Boolean {
        return try {
            val parsedColor = hexColor.toColorInt()
            activity.runOnUiThread {
                try {
                    val window = activity.window
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    // Update Status Bar (Top)
                    window.statusBarColor = parsedColor
                    controller.isAppearanceLightStatusBars = !isDarkMode

                    // Update Navigation Bar (Bottom)
                    window.navigationBarColor = parsedColor
                    controller.isAppearanceLightNavigationBars = !isDarkMode
                } catch (e: Exception) {
                    android.util.Log.e("WebAppInterface", "UI Thread Error: ${e.message}")
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "Invalid Color or Bridge Error: ${e.message}")
            false
        }
    }

    @JavascriptInterface
    fun openSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
        val uri = android.net.Uri.fromParts("package", activity.packageName, null)
        intent.data = uri
        activity.startActivity(intent)
    }
}