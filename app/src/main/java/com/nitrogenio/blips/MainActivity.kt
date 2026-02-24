package com.nitrogenio.blips

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.gms.location.LocationServices
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraImageFile: File? = null

    // For Geolocation callback storage
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var noConnectionView: View? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileChooserCallback == null) return@registerForActivityResult

        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data == null || (data.data == null && data.clipData == null)) {
                // Check if camera file exists and has content
                if (cameraImageFile?.exists() == true && cameraImageFile!!.length() > 0) {
                    results = cameraImageUri?.let { arrayOf(it) }
                }
            } else {
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i ->
                        clipData.getItemAt(i).uri
                    }
                } else {
                    data.data?.let {
                        results = arrayOf(it)
                    }
                }
            }
        }

        fileChooserCallback?.onReceiveValue(results)
        fileChooserCallback = null
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        // Handle Geolocation callback from WebView if it was waiting
        geolocationCallback?.let { callback ->
            geolocationOrigin?.let { origin ->
                callback.invoke(origin, locationGranted, false)
            }
            geolocationCallback = null
            geolocationOrigin = null
        }

        if (locationGranted) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('android-permission-granted'))", null)
        } else {
            webView.evaluateJavascript("window.dispatchEvent(new Event('android-permission-denied'))", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        noConnectionView = findViewById(R.id.noConnectionOverlay)

        val retryButton = findViewById<Button>(R.id.retryButton)
        retryButton?.setOnClickListener {
            webView.reload()
        }

        monitorNetwork()

        if (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = findViewById(R.id.webView)
        setupWebView()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        checkLocationProvider()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setGeolocationEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('android-bridge-ready'))",
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                // Check if there is a camera app to handle the intent
                if (takePictureIntent.resolveActivity(packageManager) != null) {
                    try {
                        val photoFile = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "temp_blip_${System.currentTimeMillis()}.jpg")
                        cameraImageFile = photoFile
                        cameraImageUri = FileProvider.getUriForFile(this@MainActivity, "${applicationContext.packageName}.fileprovider", photoFile)
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    } catch (_: Exception) {
                        cameraImageUri = null
                        cameraImageFile = null
                    }
                }

                val contentSelectionIntent = fileChooserParams.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Select Image")
                if (cameraImageUri != null) {
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                }

                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (_: ActivityNotFoundException) {
                    fileChooserCallback = null
                    return false
                }

                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false)
                } else {
                    geolocationCallback = callback
                    geolocationOrigin = origin
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }
            }
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        webView.addJavascriptInterface(WebAppInterface(this, webView, fusedLocationClient), "AndroidBridge")

        val data: Uri? = intent?.data
        if (data != null) {
            webView.loadUrl(data.toString())
        } else {
            webView.loadUrl(getInitialUrl())
        }
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getInitialUrl(): String {
        val baseUrl = "https://getblips.app"
        val supportedLanguages = listOf("pt", "es", "de", "fr", "en")
        val systemLang = Locale.getDefault().language

        return if (supportedLanguages.contains(systemLang)) {
            "$baseUrl/$systemLang"
        } else {
            "$baseUrl/en"
        }
    }

    private fun checkLocationProvider() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_disabled_title)
                .setMessage(R.string.location_disabled_message)
                .setPositiveButton(R.string.settings_button) { _, _ ->
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel_button) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun monitorNetwork() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Initial check
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        noConnectionView?.visibility = if (isConnected) View.GONE else View.VISIBLE

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    noConnectionView?.visibility = View.GONE
                    webView.reload()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    noConnectionView?.visibility = View.VISIBLE
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
    }
}
