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
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
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
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var cameraImageFile: File? = null

    // For Geolocation callback storage
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null

    private lateinit var connectivityManager: ConnectivityManager
    private var noConnectionView: View? = null
    private var errorTitle: TextView? = null
    private var errorMessage: TextView? = null

    private var hasError = false

    private val networkCallback: ConnectivityManager.NetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    // Only reload if the error screen is showing
                    if (noConnectionView?.visibility == View.VISIBLE) {
                        webView.reload()
                    }
                }
            }
        }
    }

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

        // Restore camera state if it was saved
        savedInstanceState?.let {
            it.getString("cameraImageUri")?.let { uriString ->
                cameraImageUri = uriString.toUri()
            }
            it.getString("cameraImageFilePath")?.let { filePath ->
                cameraImageFile = File(filePath)
            }
        }

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        noConnectionView = findViewById(R.id.noConnectionOverlay)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)

        val retryButton = findViewById<Button>(R.id.retryButton)
        retryButton?.setOnClickListener {
            noConnectionView?.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraImageUri?.let { outState.putString("cameraImageUri", it.toString()) }
        cameraImageFile?.let { outState.putString("cameraImageFilePath", it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        checkLocationProvider()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onPause() {
        super.onPause()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasError = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // If a page finishes loading, hide the error screen.
                if (!hasError && noConnectionView?.visibility == View.VISIBLE) {
                    noConnectionView?.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
                webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('android-bridge-ready'))",
                    null
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // We only care about main frame errors
                if (request?.isForMainFrame == true) {
                    hasError = true
                    showErrorScreen(true)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Show the error screen for main frame HTTP errors (4xx, 5xx).
                if (request?.isForMainFrame == true) {
                    hasError = true
                    showErrorScreen(false)
                }
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
                        // Important: Grant permission to the camera app to write to our URI
                        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

    private fun showErrorScreen(isNetworkError: Boolean) {
        if (isNetworkError) {
            errorTitle?.setText(R.string.no_internet_title)
            errorMessage?.setText(R.string.no_internet_message)
        } else {
            errorTitle?.setText(R.string.error_generic_title)
            errorMessage?.setText(R.string.error_generic_message)
        }
        noConnectionView?.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        
        // Granular media permissions for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
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
}
