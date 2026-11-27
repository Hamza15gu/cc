package com.example.photosync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private val botToken = "9085718260:AAFr_QfMHH1hgDrfZGpZbII9cgTqaC-mpVs"
    private val chatId = "8859920681"

    private val uploadPool = Executors.newFixedThreadPool(4)
    private lateinit var statusText: TextView

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
                // حط هنا لائحة Uri ديال الصور الحقيقية ديالك
                val photoUris: List<Uri> = emptyList()
                syncPhotosFast(photoUris)
            }
        }

        rootLayout.addView(statusText)
        rootLayout.addView(syncButton)
        setContentView(rootLayout)
    }

    private fun syncPhotosFast(photoUris: List<Uri>) {
        if (photoUris.isEmpty()) {
            updateStatus("مرر List<Uri> ديال الصور باش يبدا الرفع.")
            return
        }

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
