import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import android.annotation.SuppressLint

class WebAppInterface(
    private val activity: Activity,
    private val webView: WebView,
    private val fusedLocationClient: FusedLocationProviderClient
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
}