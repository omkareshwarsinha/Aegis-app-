package com.example

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var torchState = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private const val CHANNEL_ID = "aegis_assistant_channel"
        private const val NOTIFICATION_ID = 5001
        const val ACTION_START_VOICE = "com.example.aegis.ACTION_START_VOICE"
        const val ACTION_TOGGLE_TORCH = "com.example.aegis.ACTION_TOGGLE_TORCH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-create WebView HTTP cache / Code cache directories to prevent Chromium opendir/creation Errors
        try {
            val cacheDir = this.cacheDir
            val webViewDir = java.io.File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            java.io.File(webViewDir, "js").mkdirs()
            java.io.File(webViewDir, "wasm").mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Request necessary permissions at startup
        requestPermissionsIfNeeded()

        // Create notification channel for Tasker-style ongoing notifications
        createNotificationChannel()

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        // Setup WebView
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(WebAppInterface(this@MainActivity, this), "app")
        }

        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)

        // Show a tasker-like notification
        showOngoingNotification()

        // Process any incoming intent triggers, e.g. from widgets or notifications
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
         super.onNewIntent(intent)
         handleIntent(intent)
     }
 
     private fun handleIntent(intent: Intent?) {
         when (intent?.action) {
             ACTION_START_VOICE -> {
                 webView.postDelayed({
                     try {
                         webView.evaluateJavascript("javascript:window.triggerVoiceFromNative()", null)
                     } catch (e: Exception) {
                         e.printStackTrace()
                     }
                 }, 1000)
             }
             ACTION_TOGGLE_TORCH -> {
                 try {
                     torchState = !torchState
                     val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                     val cameraId = cameraManager.cameraIdList[0]
                     cameraManager.setTorchMode(cameraId, torchState)
                     Toast.makeText(this, "Aegis: Flashlight " + (if (torchState) "ON" else "OFF"), Toast.LENGTH_SHORT).show()
                     webView.post {
                         try {
                             webView.evaluateJavascript("javascript:try { if(window.setIsTorchOn) { setIsTorchOn($torchState); } } catch(e){}", null)
                         } catch (e: Exception) {
                             e.printStackTrace()
                         }
                     }
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
             }
         }
     }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                tts?.language = Locale.getDefault()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun speak(text: String, languageCode: String = "") {
        try {
            val localeOfChoice = when {
                languageCode.lowercase(Locale.ROOT).startsWith("hi") || languageCode.lowercase(Locale.ROOT) == "hinglish" || text.any { it.code in 0x0900..0x097F } -> {
                    Locale("hi", "IN")
                }
                else -> Locale.getDefault()
            }
            tts?.language = localeOfChoice
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startSpeechRecognizer(languageCode: String = "") {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        speechRecognizer?.destroy()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    try {
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                            setRecognitionListener(AegisSpeechListener())
                        }

                        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            
                            val localeOfChoice = when (languageCode.lowercase(Locale.ROOT)) {
                                "hindi", "hinglish", "hi" -> Locale("hi", "IN")
                                else -> Locale.getDefault()
                            }
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeOfChoice.toString())
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeOfChoice.toString())
                            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, localeOfChoice.toString())
                            
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        }

                        speechRecognizer?.startListening(recognizerIntent)
                        isListening = true
                        webView.evaluateJavascript("javascript:window.onSpeechStateChange('listening')", null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, "Speech recognition failed to start", Toast.LENGTH_SHORT).show()
                        webView.evaluateJavascript("javascript:window.onSpeechStateChange('error')", null)
                    }
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_PERMISSIONS)
                }
            } else {
                Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
                try {
                    webView.evaluateJavascript("javascript:window.onSpeechStateChange('unavailable')", null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun stopSpeechRecognizer() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isListening = false
            try {
                webView.evaluateJavascript("javascript:window.onSpeechStateChange('idle')", null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.VIBRATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Aegis Voice Assistant"
            val descriptionText = "Persistent notification for Aegis Voice Control"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showOngoingNotification() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_START_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val speakPendingIntent = PendingIntent.getActivity(
            this, 1, speakIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val torchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_TOGGLE_TORCH
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val torchPendingIntent = PendingIntent.getActivity(
            this, 2, torchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Aegis Siri Assistant")
            .setContentText("Aegis represents siri-like controls. Tap triggers voice.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "🎙 Speak", speakPendingIntent)
            .addAction(android.R.drawable.ic_menu_compass, "💡 Toggle Light", torchPendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    inner class AegisSpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // Send mic volume levels back to index.html for beautiful reactive wave animations!
            webView.post {
                webView.evaluateJavascript("javascript:window.onSpeechRmsChanged($rmsdB)", null)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            webView.post {
                webView.evaluateJavascript("javascript:window.onSpeechStateChange('processing')", null)
            }
        }
        override fun onError(error: Int) {
            webView.post {
                webView.evaluateJavascript("javascript:window.onSpeechStateChange('error')", null)
            }
        }
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val bestMatch = matches[0]
                webView.post {
                    webView.evaluateJavascript("javascript:window.onSpeechResult('${bestMatch.escapeJs()}')", null)
                }
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partialMatch = matches[0]
                webView.post {
                    webView.evaluateJavascript("javascript:window.onSpeechPartialResult('${partialMatch.escapeJs()}')", null)
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

class WebAppInterface(private val activity: MainActivity, private val webView: WebView) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    @JavascriptInterface
    fun LaunchApp(packageName: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    activity.startActivity(launchIntent)
                } else {
                    // Fallback: search on Play Store if not installed
                    val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    activity.startActivity(playIntent)
                }
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }
    }

    @JavascriptInterface
    fun OpenUrl(url: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "https://$url"
                } else {
                    url
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun MakeCall(number: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun Speak(text: String) {
        try {
            activity.speak(text, "")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun Speak(text: String, languageCode: String) {
        try {
            activity.speak(text, languageCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun StartListening() {
        try {
            activity.startSpeechRecognizer("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun StartListening(languageCode: String) {
        try {
            activity.startSpeechRecognizer(languageCode)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun StopListening() {
        try {
            activity.stopSpeechRecognizer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun SetFlashlight(enabled: Boolean) {
        try {
            val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun Vibrate(millis: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(millis)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun GetBatteryLevel(): Int {
        try {
            val batteryManager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            e.printStackTrace()
            return 50
        }
    }

    @JavascriptInterface
    fun SetVolume(type: String, level: Int) {
        try {
            val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (type.lowercase(Locale.ROOT)) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "music", "media" -> AudioManager.STREAM_MUSIC
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audioManager.getStreamMaxVolume(streamType)
            val computedLevel = (max * (level / 100.0)).toInt().coerceIn(0, max)
            audioManager.setStreamVolume(streamType, computedLevel, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun GetInstalledApps(): String {
        try {
            val appList = JSONArray()
            val pm = activity.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in packages) {
                if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val pkg = appInfo.packageName
                    val obj = JSONObject().apply {
                        put("name", name)
                        put("package", pkg)
                    }
                    appList.put(obj)
                }
            }
            return appList.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return "[]"
        }
    }

    @JavascriptInterface
    fun GetSystemInfo(): String {
        try {
            val info = JSONObject()
            val batteryManager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            var isConnected = false
            var isWifi = false
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
            }

            info.put("battery", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            info.put("charging", batteryManager.isCharging)
            info.put("online", isConnected)
            info.put("connectionType", if (isWifi) "WiFi" else "Mobile")
            info.put("time", System.currentTimeMillis())

            return info.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return "{}"
        }
    }

    @JavascriptInterface
    fun SendSMS(number: String, msg: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", msg)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @JavascriptInterface
    fun ShowTaskerNotification(title: String, message: String) {
        try {
            val builder = NotificationCompat.Builder(activity, "aegis_assistant_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun GetContacts(): String {
        val contactsList = JSONArray()
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val resolver = activity.contentResolver
                val cursor = resolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null, null, null
                )
                cursor?.use {
                    val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val addedNumbers = HashSet<String>()
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: ""
                        val number = cursor.getString(numIdx) ?: ""
                        val cleanNum = number.replace(" ", "").replace("-", "")
                        if (!addedNumbers.contains(cleanNum) && cleanNum.isNotEmpty()) {
                            addedNumbers.add(cleanNum)
                            val obj = JSONObject().apply {
                                put("name", name)
                                put("number", cleanNum)
                            }
                            contactsList.put(obj)
                        }
                    }
                }
            } else {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 102)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return contactsList.toString()
    }

    @JavascriptInterface
    fun CallCustomApi(url: String, method: String, headersJson: String, bodyStr: String, callbackJs: String) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBuilder = Request.Builder().url(url)
        
        try {
            if (headersJson.isNotEmpty()) {
                val headersObj = JSONObject(headersJson)
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headersObj.getString(key)
                    requestBuilder.addHeader(key, value)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (method.uppercase(Locale.ROOT) == "POST") {
            requestBuilder.post(bodyStr.toRequestBody(mediaType))
        } else if (method.uppercase(Locale.ROOT) == "PUT") {
            requestBuilder.put(bodyStr.toRequestBody(mediaType))
        } else {
            requestBuilder.get()
        }

        val request = requestBuilder.build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    try {
                        webView.evaluateJavascript("javascript:window.$callbackJs('Error: ${e.message?.escapeJs()}')", null)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        if (response.isSuccessful) {
                            try {
                                webView.evaluateJavascript("javascript:window.$callbackJs('${responseBody.escapeJs()}')", null)
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        } else {
                            try {
                                webView.evaluateJavascript("javascript:window.$callbackJs('Http Error: ${response.code}')", null)
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        }
                    }
                }
            }
        })
    }

    @JavascriptInterface
    fun IsGeminiKeyConfigured(): Boolean {
        try {
            val apiKey = BuildConfig.GEMINI_API_KEY
            return apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"
        } catch (e: Exception) {
            return false
        }
    }

    @JavascriptInterface
    fun CallGemini(prompt: String, callbackJs: String) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                try {
                    webView.evaluateJavascript("javascript:$callbackJs('Error: Please configure your Gemini API Key in the AI Studio Secrets Panel before using the Gemini AI brain.')", null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        val model = "gemini-3.5-flash"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = JSONObject().apply {
            val contentsArray = JSONArray()
            val textPart = JSONObject().put("text", prompt)
            val partsArray = JSONArray().put(textPart)
            val contentObj = JSONObject().put("parts", partsArray)
            contentsArray.put(contentObj)
            put("contents", contentsArray)
            put("contents", contentsArray)
        }.toString()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(requestBody.toRequestBody(mediaType))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    try {
                        webView.evaluateJavascript("javascript:$callbackJs('Error: ${e.message?.escapeJs()}')", null)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val callbackResult = if (response.isSuccessful && responseBody.isNotEmpty()) {
                            try {
                                val jsonObject = JSONObject(responseBody)
                                val candidates = jsonObject.getJSONArray("candidates")
                                val firstCandidate = candidates.getJSONObject(0)
                                val content = firstCandidate.getJSONObject("content")
                                val parts = content.getJSONArray("parts")
                                parts.getJSONObject(0).getString("text")
                            } catch (e: Exception) {
                                "Error parsing response: ${e.message}"
                            }
                        } else {
                            "Error ${response.code}: API returned failure response"
                        }
                        try {
                            webView.evaluateJavascript("javascript:$callbackJs('${callbackResult.escapeJs()}')", null)
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        })
    }
}

fun String.escapeJs(): String {
    return this.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
