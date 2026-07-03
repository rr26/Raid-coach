package com.raidcoach.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.raidcoach.app.action.STOP_OVERLAY"
        const val ACTION_PROJECTION_GRANTED = "com.raidcoach.app.action.PROJECTION_GRANTED"
        const val ACTION_PROJECTION_DENIED = "com.raidcoach.app.action.PROJECTION_DENIED"
        const val ACTION_EXPAND_PANEL = "com.raidcoach.app.action.EXPAND_PANEL"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val FOREGROUND_CHANNEL_ID = "overlay_service_channel"
        private const val REPLY_CHANNEL_ID = "coach_reply_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val REPLY_NOTIFICATION_ID = 2

        private const val BUBBLE_SIZE_DP = 56
        private const val COACH_BUTTON_SIZE_DP = 26
        private const val RED_DOT_SIZE_DP = 12
        private const val PANEL_WIDTH_FRACTION = 0.4f
        private const val AUTO_CAPTURE_INTERVAL_MS = 60_000L
        private const val MAX_HISTORY_MESSAGES = 30 // 15 exchanges

        private const val SYSTEM_PROMPT_PREFIX = "You are a Raid: Shadow Legends coach. Analyze each screenshot " +
            "of my gameplay. Give short, actionable advice: what to do this turn, misplays you spot, better " +
            "skill order, team or gear improvements. Be concise — 2-3 sentences unless asked. If the screen " +
            "shows nothing actionable, reply \"watching\"."
    }

    private val overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var securePrefs: SecurePrefs
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var bubbleView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleRedDotView: View? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isExpanded = false
    private var listViewRef: ListView? = null
    private var modeButtonRef: Button? = null
    private var pauseButtonRef: Button? = null

    private var mediaProjection: MediaProjection? = null
    private var captureController: ScreenCaptureController? = null
    private var captureMode = CaptureMode.ON_DEMAND
    private var isAutoPaused = false
    private var autoLoopJob: Job? = null
    private var lastAutoFrame: Bitmap? = null
    private var pendingAction: (() -> Unit)? = null

    private val displayMessages = mutableListOf<String>()
    private lateinit var messageAdapter: ArrayAdapter<String>
    private val history = mutableListOf<ApiMessage>()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            captureController?.release()
            captureController = null
            mediaProjection = null
            lastAutoFrame?.recycle()
            lastAutoFrame = null
            if (captureMode == CaptureMode.AUTO) {
                captureMode = CaptureMode.ON_DEMAND
            }
            refreshControlsUi()
            updateBubbleIndicator()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        securePrefs = SecurePrefs.getInstance(this)
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayMessages)

        bubbleView = createBubbleView()
        bubbleParams = createBubbleParams(dp(BUBBLE_SIZE_DP))
        windowManager.addView(bubbleView, bubbleParams)

        startAutoCaptureLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureForeground()

        when (action) {
            ACTION_PROJECTION_GRANTED -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val resultData = intent?.parcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                if (resultData != null) {
                    onProjectionGranted(resultCode, resultData)
                } else {
                    onProjectionDenied()
                }
            }

            ACTION_PROJECTION_DENIED -> onProjectionDenied()

            ACTION_EXPAND_PANEL -> expandOverlay()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        autoLoopJob?.cancel()
        serviceScope.cancel()

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        captureController?.release()
        lastAutoFrame?.recycle()

        runCatching { windowManager.removeView(bubbleView) }
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
    }

    // region Media projection

    private fun onProjectionGranted(resultCode: Int, data: Intent) {
        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
        projection.registerCallback(projectionCallback, mainHandler)
        mediaProjection = projection
        captureController = ScreenCaptureController(this, projection)

        val action = pendingAction
        pendingAction = null
        action?.invoke()

        refreshControlsUi()
        updateBubbleIndicator()
    }

    private fun onProjectionDenied() {
        pendingAction = null
        if (captureMode == CaptureMode.AUTO) {
            captureMode = CaptureMode.ON_DEMAND
        }
        appendDisplayMessage("Coach", "Screen capture permission was not granted.")
        refreshControlsUi()
        updateBubbleIndicator()
    }

    private fun launchProjectionConsent() {
        startActivity(
            Intent(this, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun startAutoCaptureLoop() {
        autoLoopJob = serviceScope.launch {
            while (isActive) {
                delay(AUTO_CAPTURE_INTERVAL_MS)
                if (captureMode == CaptureMode.AUTO && !isAutoPaused && mediaProjection != null) {
                    performAutoCapture()
                }
            }
        }
    }

    private suspend fun performAutoCapture() {
        val controller = captureController ?: return
        val bitmap = controller.captureBitmap() ?: return

        val previous = lastAutoFrame
        if (previous != null && isNearlyIdentical(previous, bitmap)) {
            bitmap.recycle()
            return
        }

        previous?.recycle()
        lastAutoFrame = bitmap
        sendCapturedFrame(bitmap)
    }

    private fun performImmediateCapture() {
        val controller = captureController ?: return
        serviceScope.launch {
            val bitmap = controller.captureBitmap()
            if (bitmap == null) {
                appendDisplayMessage("Coach", "Couldn't capture the screen. Try again.")
                return@launch
            }
            sendCapturedFrame(bitmap)
            bitmap.recycle()
        }
    }

    private suspend fun sendCapturedFrame(bitmap: Bitmap) {
        appendDisplayMessage("You", "[Screenshot]")

        val scaled = downscaleForUpload(bitmap)
        val base64 = withContext(Dispatchers.Default) { bitmapToJpegBase64(scaled) }
        if (scaled !== bitmap) scaled.recycle()

        appendHistory(ApiMessage(role = "user", blocks = listOf(ApiContentBlock(type = "image", imageBase64 = base64))))
        requestCoachReply()
    }

    // endregion

    // region Bubble

    private fun createBubbleView(): View {
        val bubbleSize = dp(BUBBLE_SIZE_DP)
        val coachButtonSize = dp(COACH_BUTTON_SIZE_DP)
        val dotSize = dp(RED_DOT_SIZE_DP)

        val container = FrameLayout(this)

        val circle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 33, 150, 243))
            }
        }
        container.addView(circle, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        attachBubbleTouchListener(circle)

        val coachButton = TextView(this).apply {
            text = "⚡"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 255, 152, 0))
            }
            setOnClickListener { onCoachMeClicked() }
        }
        container.addView(
            coachButton,
            FrameLayout.LayoutParams(coachButtonSize, coachButtonSize, Gravity.BOTTOM or Gravity.END)
        )

        val redDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            visibility = View.GONE
        }
        container.addView(redDot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END))
        bubbleRedDotView = redDot

        return container
    }

    private fun createBubbleParams(sizePx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(100)
        }
    }

    private fun attachBubbleTouchListener(dragHandle: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        bubbleParams.x = initialX + dx.toInt()
                        bubbleParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandOverlay()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun onCoachMeClicked() {
        if (mediaProjection == null) {
            pendingAction = { performImmediateCapture() }
            launchProjectionConsent()
        } else {
            performImmediateCapture()
        }
    }

    private fun updateBubbleIndicator() {
        val active = captureMode == CaptureMode.AUTO && !isAutoPaused
        bubbleRedDotView?.visibility = if (active) View.VISIBLE else View.GONE
    }

    // endregion

    // region Panel

    private fun expandOverlay() {
        if (isExpanded) return

        val panel = panelView ?: createPanelView().also { panelView = it }
        val params = panelParams ?: createPanelParams().also { panelParams = it }

        windowManager.removeView(bubbleView)
        windowManager.addView(panel, params)
        isExpanded = true
    }

    private fun collapseOverlay() {
        if (!isExpanded) return

        panelView?.let { windowManager.removeView(it) }
        windowManager.addView(bubbleView, bubbleParams)
        isExpanded = false
    }

    private fun createPanelParams(): WindowManager.LayoutParams {
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * PANEL_WIDTH_FRACTION).toInt()

        return WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - panelWidth
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun createPanelView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 255, 255, 255))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "Raid Coach"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, SettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }

        val collapseButton = Button(this).apply {
            text = "Collapse"
            setOnClickListener { collapseOverlay() }
        }

        header.addView(title)
        header.addView(settingsButton)
        header.addView(collapseButton)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val modeButton = Button(this).apply {
            text = modeButtonLabel()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onModeButtonClicked() }
        }
        modeButtonRef = modeButton

        val pauseButton = Button(this).apply {
            text = pauseButtonLabel()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onPauseButtonClicked() }
        }
        pauseButtonRef = pauseButton

        controlsRow.addView(modeButton)
        controlsRow.addView(pauseButton)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
            adapter = messageAdapter
        }
        listViewRef = listView

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val input = EditText(this).apply {
            hint = "Ask the coach"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    input.text.clear()
                    onSendManualMessage(text)
                }
            }
        }

        inputRow.addView(input)
        inputRow.addView(sendButton)

        root.addView(header)
        root.addView(controlsRow)
        root.addView(listView)
        root.addView(inputRow)

        return root
    }

    private fun onModeButtonClicked() {
        val newMode = if (captureMode == CaptureMode.AUTO) CaptureMode.ON_DEMAND else CaptureMode.AUTO
        captureMode = newMode

        if (newMode == CaptureMode.AUTO) {
            isAutoPaused = false
            if (mediaProjection == null) {
                pendingAction = null
                launchProjectionConsent()
            }
        }

        refreshControlsUi()
        updateBubbleIndicator()
    }

    private fun onPauseButtonClicked() {
        isAutoPaused = !isAutoPaused
        refreshControlsUi()
        updateBubbleIndicator()
    }

    private fun refreshControlsUi() {
        modeButtonRef?.text = modeButtonLabel()
        pauseButtonRef?.text = pauseButtonLabel()
    }

    private fun modeButtonLabel(): String = "Mode: ${if (captureMode == CaptureMode.AUTO) "AUTO" else "ON-DEMAND"}"

    private fun pauseButtonLabel(): String = if (isAutoPaused) "Resume" else "Pause"

    private fun onSendManualMessage(text: String) {
        appendDisplayMessage("You", text)
        appendHistory(ApiMessage(role = "user", blocks = listOf(ApiContentBlock(type = "text", text = text))))
        serviceScope.launch { requestCoachReply() }
    }

    // endregion

    // region Coach conversation

    private suspend fun requestCoachReply() {
        val apiKey = securePrefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            appendDisplayMessage("Coach", "No API key set. Open Settings to add one.")
            return
        }

        val systemPrompt = buildSystemPrompt(securePrefs.getBriefing().orEmpty())
        val result = AnthropicClient.sendMessage(apiKey, systemPrompt, history)

        result.onSuccess { reply ->
            appendHistory(ApiMessage(role = "assistant", blocks = listOf(ApiContentBlock(type = "text", text = reply))))
            appendDisplayMessage("Coach", reply)
            if (!isExpanded) showReplyNotification(reply)
        }.onFailure { error ->
            appendDisplayMessage("Coach", "Error: ${error.message ?: "request failed"}")
        }
    }

    private fun buildSystemPrompt(briefing: String): String =
        if (briefing.isBlank()) SYSTEM_PROMPT_PREFIX else "$SYSTEM_PROMPT_PREFIX\n\n$briefing"

    private fun appendHistory(message: ApiMessage) {
        history.add(message)
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeAt(0)
        }
    }

    private fun appendDisplayMessage(label: String, text: String) {
        displayMessages.add("$label: $text")
        messageAdapter.notifyDataSetChanged()
        listViewRef?.setSelection(messageAdapter.count - 1)
    }

    // endregion

    // region Notifications

    private fun ensureForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        createNotificationChannelsIfNeeded()

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Raid Coach overlay")
            .setContentText("Overlay bubble is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop overlay", stopPendingIntent)
            .build()
    }

    private fun showReplyNotification(reply: String) {
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val expandIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_EXPAND_PANEL }
        val expandPendingIntent = PendingIntent.getService(
            this,
            1,
            expandIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, REPLY_CHANNEL_ID)
            .setContentTitle("Raid Coach")
            .setContentText(reply.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(expandPendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(REPLY_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannelsIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL_ID, "Overlay service", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(REPLY_CHANNEL_ID, "Coach replies", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    // endregion

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
