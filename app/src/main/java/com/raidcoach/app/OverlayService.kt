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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        private const val MIN_PANEL_WIDTH_DP = 250
        private const val MIN_PANEL_HEIGHT_DP = 300
        private const val MAX_PANEL_SIZE_FRACTION = 0.9f
        private const val RESIZE_HANDLE_SIZE_DP = 28
        private const val THUMBNAIL_SIZE_DP = 40
        private const val HIDE_BEFORE_CAPTURE_DELAY_MS = 120L
        private const val AUTO_CAPTURE_INTERVAL_MS = 60_000L
        private const val MAX_HISTORY_MESSAGES = 30 // 15 exchanges
        private const val RECENT_IMAGE_MESSAGE_COUNT = 3
        private const val WATCHING_REPLY = "watching"

        private const val MIN_PANEL_OPACITY_PERCENT = 30
        private const val MAX_PANEL_OPACITY_PERCENT = 100
        private const val DEFAULT_PANEL_OPACITY_PERCENT = 68
        private const val PANEL_BG_R = 18
        private const val PANEL_BG_G = 18
        private const val PANEL_BG_B = 22
        private const val BUBBLE_ROW_BG_ALPHA = 60

        private const val DETAIL_SCAN_JPEG_QUALITY = 90
        private const val MIN_CROP_SIZE_DP = 48
        private const val CROP_PREVIEW_WIDTH_FRACTION = 0.85f
        private const val CROP_PREVIEW_HEIGHT_FRACTION = 0.6f
        private const val DETAIL_SCAN_PREFIX = "This is a high-detail crop of a champion's stat panel — read " +
            "all names and numbers precisely"

        private const val SYSTEM_PROMPT_PREFIX = "You are a Raid: Shadow Legends coach watching my gameplay via " +
            "screenshots. Give short, actionable advice: what to do this turn, misplays you spot, better skill " +
            "order, team/gear/build improvements, alternative strategies. Be concise — 2-3 sentences unless I " +
            "ask for more. If a screenshot shows nothing actionable, reply exactly 'watching'."
    }

    private val overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var securePrefs: SecurePrefs
    private lateinit var panelLayoutPrefs: PanelLayoutPrefs
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var chatDatabase: ChatDatabase

    private lateinit var bubbleView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleRedDotView: View? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelContentView: LinearLayout? = null
    private var isExpanded = false
    private var listViewRef: ListView? = null
    private var modeButtonRef: Button? = null
    private var pauseButtonRef: Button? = null
    private var pendingThumbnailContainer: View? = null
    private var pendingThumbnailImageView: ImageView? = null
    private var pendingThumbnailLabelView: TextView? = null
    private var panelOpacityPercent = DEFAULT_PANEL_OPACITY_PERCENT

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionTypeActive = false
    private var captureController: ScreenCaptureController? = null
    private var captureMode = CaptureMode.ON_DEMAND
    private var isAutoPaused = false
    private var autoLoopJob: Job? = null
    private var lastAutoFrame: Bitmap? = null
    private var pendingAction: (() -> Unit)? = null
    private var pendingCaptureBitmap: Bitmap? = null
    private var pendingCaptureIsDetailScan = false
    private var isCapturingPendingAttachment = false

    private var scanChoiceView: View? = null
    private var cropOverlayView: View? = null
    private var cropFullResBitmap: Bitmap? = null
    private var cropPreviewBitmap: Bitmap? = null

    private val displayMessages = mutableListOf<DisplayEntry>()
    private lateinit var messageAdapter: ChatAdapter
    private val history = mutableListOf<ApiMessage>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var typingEntry: DisplayEntry? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            captureController?.release()
            captureController = null
            mediaProjection = null
            mediaProjectionTypeActive = false
            ensureForeground()
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
        panelLayoutPrefs = PanelLayoutPrefs.getInstance(this)
        chatDatabase = ChatDatabase.getInstance(this)
        panelOpacityPercent = panelLayoutPrefs.getOpacityPercent(DEFAULT_PANEL_OPACITY_PERCENT)
        messageAdapter = ChatAdapter()

        bubbleView = createBubbleView()
        bubbleParams = createBubbleParams(dp(BUBBLE_SIZE_DP))
        windowManager.addView(bubbleView, bubbleParams)

        startAutoCaptureLoop()
        loadPersistedHistory()
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
        pendingCaptureBitmap?.recycle()
        cropFullResBitmap?.recycle()
        cropPreviewBitmap?.recycle()
        displayMessages.forEach { it.thumbnail?.recycle() }

        runCatching { windowManager.removeView(bubbleView) }
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        scanChoiceView?.let { view -> runCatching { windowManager.removeView(view) } }
        cropOverlayView?.let { view -> runCatching { windowManager.removeView(view) } }
    }

    // region Media projection

    private fun onProjectionGranted(resultCode: Int, data: Intent) {
        // Android 14+ requires the FGS to already be running with the mediaProjection type
        // before getMediaProjection() is called, and rejects requesting that type before
        // consent exists — so promote only now, never at initial service startup.
        mediaProjectionTypeActive = true
        ensureForeground()

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
        appendDisplayMessage("Coach", "Screen capture permission was not granted.", isError = true)
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
        val bitmap = captureWithOverlayHidden() ?: return

        val previous = lastAutoFrame
        if (previous != null && isNearlyIdentical(previous, bitmap)) {
            bitmap.recycle()
            return
        }

        previous?.recycle()
        lastAutoFrame = bitmap
        sendCapturedFrame(bitmap, "Auto-screenshot sent", isAutoScreenshot = true)
    }

    private fun performImmediateCapture() {
        serviceScope.launch {
            val bitmap = captureWithOverlayHidden()
            if (bitmap == null) {
                appendDisplayMessage("Coach", "Couldn't capture the screen. Try again.", isError = true)
                return@launch
            }
            sendCapturedFrame(bitmap, "[Screenshot]", isAutoScreenshot = false)
            bitmap.recycle()
        }
    }

    // Hides the bubble/panel for one frame so the coach sees the game, not our own overlay.
    private suspend fun captureWithOverlayHidden(): Bitmap? {
        val controller = captureController ?: return null
        hideOverlayViews()
        return try {
            delay(HIDE_BEFORE_CAPTURE_DELAY_MS)
            controller.captureBitmap()
        } finally {
            showOverlayViews()
        }
    }

    private fun hideOverlayViews() {
        bubbleView.visibility = View.INVISIBLE
        panelView?.visibility = View.INVISIBLE
    }

    private fun showOverlayViews() {
        bubbleView.visibility = View.VISIBLE
        panelView?.visibility = View.VISIBLE
    }

    private suspend fun sendCapturedFrame(bitmap: Bitmap, label: String, isAutoScreenshot: Boolean) {
        val thumbnail = Bitmap.createScaledBitmap(bitmap, dp(THUMBNAIL_SIZE_DP), dp(THUMBNAIL_SIZE_DP), true)
        appendDisplayMessage("You", label, thumbnail, isAutoScreenshot = isAutoScreenshot)

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
        val screenHeight = resources.displayMetrics.heightPixels

        val defaultWidth = (screenWidth * PANEL_WIDTH_FRACTION).toInt()
            .coerceIn(minPanelWidthPx(), maxPanelWidthPx())
        val defaultHeight = maxPanelHeightPx()

        val width = panelLayoutPrefs.getWidth(defaultWidth).coerceIn(minPanelWidthPx(), maxPanelWidthPx())
        val height = panelLayoutPrefs.getHeight(defaultHeight).coerceIn(minPanelHeightPx(), maxPanelHeightPx())

        val defaultX = screenWidth - width
        val defaultY = 0
        val x = panelLayoutPrefs.getX(defaultX).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val y = panelLayoutPrefs.getY(defaultY).coerceIn(0, (screenHeight - height).coerceAtLeast(0))

        return WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun minPanelWidthPx() = dp(MIN_PANEL_WIDTH_DP)
    private fun minPanelHeightPx() = dp(MIN_PANEL_HEIGHT_DP)
    private fun maxPanelWidthPx() = (resources.displayMetrics.widthPixels * MAX_PANEL_SIZE_FRACTION).toInt()
    private fun maxPanelHeightPx() = (resources.displayMetrics.heightPixels * MAX_PANEL_SIZE_FRACTION).toInt()

    private fun persistPanelLayout() {
        val params = panelParams ?: return
        panelLayoutPrefs.save(params.x, params.y, params.width, params.height)
    }

    private fun applyPanelOpacity() {
        val alpha = (panelOpacityPercent / 100f * 255).toInt().coerceIn(0, 255)
        panelContentView?.setBackgroundColor(Color.argb(alpha, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
    }

    private fun attachPanelDragListener(handle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            val params = panelParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    params.x = (initialX + dx).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                    params.y = (initialY + dy).coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
                    panelView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    persistPanelLayout()
                    true
                }

                else -> false
            }
        }
    }

    private fun attachPanelResizeListener(handle: View) {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            val params = panelParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    val newWidth = (initialWidth + dx).coerceIn(minPanelWidthPx(), maxPanelWidthPx())
                    val newHeight = (initialHeight + dy).coerceIn(minPanelHeightPx(), maxPanelHeightPx())

                    params.width = newWidth.coerceAtMost((screenWidth - params.x).coerceAtLeast(minPanelWidthPx()))
                    params.height = newHeight.coerceAtMost((screenHeight - params.y).coerceAtLeast(minPanelHeightPx()))
                    panelView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    persistPanelLayout()
                    true
                }

                else -> false
            }
        }
    }

    private fun createPanelView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        panelContentView = content
        applyPanelOpacity()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        attachPanelDragListener(header)

        val title = TextView(this).apply {
            text = "Raid Coach"
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearButton = Button(this).apply {
            text = "Clear"
            setOnClickListener { onClearConversationClicked() }
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
        header.addView(clearButton)
        header.addView(settingsButton)
        header.addView(collapseButton)

        val opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val opacityLabel = TextView(this).apply {
            text = "Opacity"
            setTextColor(Color.rgb(200, 200, 205))
            textSize = 12f
        }

        val opacitySeekBar = SeekBar(this).apply {
            min = MIN_PANEL_OPACITY_PERCENT
            max = MAX_PANEL_OPACITY_PERCENT
            progress = panelOpacityPercent
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    panelOpacityPercent = progress
                    applyPanelOpacity()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    panelLayoutPrefs.setOpacityPercent(panelOpacityPercent)
                }
            })
        }

        opacityRow.addView(opacityLabel)
        opacityRow.addView(opacitySeekBar)

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
            divider = null
            dividerHeight = dp(6)
        }
        listViewRef = listView

        val thumbnailSize = dp(THUMBNAIL_SIZE_DP)
        val pendingThumbnailImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(thumbnailSize, thumbnailSize)
        }
        pendingThumbnailImageView = pendingThumbnailImage

        val discardPendingButton = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 0, 0, 0))
            }
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END)
            setOnClickListener { discardPendingCapture() }
        }

        val pendingThumbnailLabel = TextView(this).apply {
            text = "Detail scan"
            setTextColor(Color.WHITE)
            textSize = 9f
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.argb(200, 0, 0, 0))
            }
            visibility = View.GONE
        }
        pendingThumbnailLabelView = pendingThumbnailLabel

        val pendingThumbnailRow = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(pendingThumbnailImage)
            addView(discardPendingButton)
            addView(pendingThumbnailLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ))
            visibility = View.GONE
        }
        pendingThumbnailContainer = pendingThumbnailRow

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val cameraButton = Button(this).apply {
            text = "📷"
            setOnClickListener { onCameraButtonClicked() }
        }

        val input = EditText(this).apply {
            hint = "Ask the coach"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(180, 180, 185))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = input.text.toString().trim()
                val attachedBitmap = pendingCaptureBitmap
                val isDetailScan = pendingCaptureIsDetailScan
                if (text.isEmpty() && attachedBitmap == null) return@setOnClickListener
                input.text.clear()
                pendingCaptureBitmap = null
                pendingCaptureIsDetailScan = false
                updatePendingThumbnailUi()
                onSendManualMessage(text, attachedBitmap, isDetailScan)
            }
        }

        inputRow.addView(cameraButton)
        inputRow.addView(input)
        inputRow.addView(sendButton)

        content.addView(header)
        content.addView(opacityRow)
        content.addView(controlsRow)
        content.addView(listView)
        content.addView(pendingThumbnailRow)
        content.addView(inputRow)

        val resizeHandle = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(160, 120, 120, 120))
            }
        }
        attachPanelResizeListener(resizeHandle)

        return FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(
                resizeHandle,
                FrameLayout.LayoutParams(dp(RESIZE_HANDLE_SIZE_DP), dp(RESIZE_HANDLE_SIZE_DP), Gravity.BOTTOM or Gravity.END)
            )
        }
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

    private fun onSendManualMessage(text: String, imageBitmap: Bitmap?, isDetailScan: Boolean) {
        val blocks = mutableListOf<ApiContentBlock>()
        var thumbnail: Bitmap? = null

        if (imageBitmap != null) {
            val encoded = if (isDetailScan) {
                bitmapToJpegBase64(imageBitmap, DETAIL_SCAN_JPEG_QUALITY)
            } else {
                bitmapToJpegBase64(imageBitmap)
            }
            blocks.add(ApiContentBlock(type = "image", imageBase64 = encoded))
            thumbnail = Bitmap.createScaledBitmap(imageBitmap, dp(THUMBNAIL_SIZE_DP), dp(THUMBNAIL_SIZE_DP), true)
        }

        val combinedText = when {
            isDetailScan && text.isNotEmpty() -> "$DETAIL_SCAN_PREFIX $text"
            isDetailScan -> DETAIL_SCAN_PREFIX
            else -> text
        }
        if (combinedText.isNotEmpty()) {
            blocks.add(ApiContentBlock(type = "text", text = combinedText))
        }
        if (blocks.isEmpty()) return

        val label = when {
            isDetailScan && text.isNotEmpty() -> "Detail scan: $text"
            isDetailScan -> "Detail scan"
            imageBitmap != null -> text.ifEmpty { "Screenshot" }
            else -> text
        }
        appendDisplayMessage("You", label, thumbnail)
        appendHistory(ApiMessage(role = "user", blocks = blocks))
        imageBitmap?.recycle()

        serviceScope.launch { requestCoachReply() }
    }

    private fun onCameraButtonClicked() {
        showScanChoiceOverlay()
    }

    private fun showScanChoiceOverlay() {
        removeScanChoiceOverlay()
        val view = createScanChoiceView()
        scanChoiceView = view
        windowManager.addView(view, createScanChoiceParams())
    }

    private fun removeScanChoiceOverlay() {
        scanChoiceView?.let { runCatching { windowManager.removeView(it) } }
        scanChoiceView = null
    }

    private fun createScanChoiceParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun createScanChoiceView(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(235, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
        }

        val title = TextView(this).apply {
            text = "Capture screenshot"
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val quickButton = Button(this).apply {
            text = "Quick"
            setOnClickListener {
                removeScanChoiceOverlay()
                onQuickCaptureChosen()
            }
        }

        val detailButton = Button(this).apply {
            text = "Detail scan"
            setOnClickListener {
                removeScanChoiceOverlay()
                onDetailScanChosen()
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { removeScanChoiceOverlay() }
        }

        card.addView(title)
        card.addView(quickButton)
        card.addView(detailButton)
        card.addView(cancelButton)
        return card
    }

    private fun onQuickCaptureChosen() {
        if (mediaProjection == null) {
            pendingAction = { captureForPendingAttachment() }
            launchProjectionConsent()
        } else {
            captureForPendingAttachment()
        }
    }

    private fun captureForPendingAttachment() {
        if (isCapturingPendingAttachment) return
        isCapturingPendingAttachment = true

        serviceScope.launch {
            try {
                val bitmap = captureWithOverlayHidden()
                if (bitmap == null) {
                    appendDisplayMessage("Coach", "Couldn't capture the screen. Try again.", isError = true)
                    return@launch
                }
                val downscaled = downscaleForUpload(bitmap)
                if (downscaled !== bitmap) bitmap.recycle()

                pendingCaptureBitmap?.recycle()
                pendingCaptureBitmap = downscaled
                pendingCaptureIsDetailScan = false
                updatePendingThumbnailUi()
            } finally {
                isCapturingPendingAttachment = false
            }
        }
    }

    private fun onDetailScanChosen() {
        if (mediaProjection == null) {
            pendingAction = { startDetailScanCapture() }
            launchProjectionConsent()
        } else {
            startDetailScanCapture()
        }
    }

    private fun startDetailScanCapture() {
        if (isCapturingPendingAttachment) return
        isCapturingPendingAttachment = true

        serviceScope.launch {
            try {
                val bitmap = captureWithOverlayHidden()
                if (bitmap == null) {
                    appendDisplayMessage("Coach", "Couldn't capture the screen. Try again.", isError = true)
                    return@launch
                }
                showCropOverlay(bitmap)
            } finally {
                isCapturingPendingAttachment = false
            }
        }
    }

    private fun showCropOverlay(fullResBitmap: Bitmap) {
        cropFullResBitmap = fullResBitmap
        val view = createCropView(fullResBitmap)
        cropOverlayView = view
        windowManager.addView(view, createCropParams())
    }

    private fun removeCropOverlay(recycleSource: Boolean) {
        cropOverlayView?.let { runCatching { windowManager.removeView(it) } }
        cropOverlayView = null
        cropPreviewBitmap?.recycle()
        cropPreviewBitmap = null
        if (recycleSource) {
            cropFullResBitmap?.recycle()
        }
        cropFullResBitmap = null
    }

    private fun createCropParams(): WindowManager.LayoutParams {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        return WindowManager.LayoutParams(
            (screenWidth * 0.92f).toInt(),
            (screenHeight * 0.85f).toInt(),
            overlayWindowType,
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun createCropView(fullResBitmap: Bitmap): View {
        val maxPreviewWidth = (resources.displayMetrics.widthPixels * CROP_PREVIEW_WIDTH_FRACTION).toInt()
        val maxPreviewHeight = (resources.displayMetrics.heightPixels * CROP_PREVIEW_HEIGHT_FRACTION).toInt()

        val previewScale = minOf(
            maxPreviewWidth.toFloat() / fullResBitmap.width,
            maxPreviewHeight.toFloat() / fullResBitmap.height
        )
        val previewWidth = (fullResBitmap.width * previewScale).toInt()
        val previewHeight = (fullResBitmap.height * previewScale).toInt()
        val previewBitmap = Bitmap.createScaledBitmap(fullResBitmap, previewWidth, previewHeight, true)
        cropPreviewBitmap = previewBitmap

        val previewImage = ImageView(this).apply {
            setImageBitmap(previewBitmap)
        }

        val cropResizeHandle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 255, 255, 255))
            }
        }

        val cropRect = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(2), Color.WHITE)
                setColor(Color.argb(50, 255, 255, 255))
            }
            addView(
                cropResizeHandle,
                FrameLayout.LayoutParams(dp(RESIZE_HANDLE_SIZE_DP), dp(RESIZE_HANDLE_SIZE_DP), Gravity.BOTTOM or Gravity.END)
            )
        }

        val defaultCropWidth = previewWidth / 2
        val cropRectParams = FrameLayout.LayoutParams(defaultCropWidth, previewHeight).apply {
            leftMargin = previewWidth - defaultCropWidth
            topMargin = 0
        }

        val previewContainer = FrameLayout(this).apply {
            addView(previewImage, FrameLayout.LayoutParams(previewWidth, previewHeight))
            addView(cropRect, cropRectParams)
        }

        attachCropDragListener(cropRect, previewWidth, previewHeight)
        attachCropResizeListener(cropResizeHandle, cropRect, previewWidth, previewHeight)

        val title = TextView(this).apply {
            text = "Drag to select the area to send in detail"
            setTextColor(Color.WHITE)
            textSize = 13f
        }

        val cancelCropButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { removeCropOverlay(recycleSource = true) }
        }

        val useCropButton = Button(this).apply {
            text = "Use crop"
            setOnClickListener {
                val params = cropRect.layoutParams as FrameLayout.LayoutParams
                onCropConfirmed(fullResBitmap, previewScale, params.leftMargin, params.topMargin, params.width, params.height)
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            addView(cancelCropButton)
            addView(useCropButton)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(245, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
            addView(title)
            addView(
                previewContainer,
                LinearLayout.LayoutParams(previewWidth, previewHeight).apply { topMargin = dp(8) }
            )
            addView(buttonRow)
        }
    }

    private fun attachCropDragListener(rect: View, previewWidth: Int, previewHeight: Int) {
        var initialLeft = 0
        var initialTop = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        rect.setOnTouchListener { _, event ->
            val params = rect.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialLeft = params.leftMargin
                    initialTop = params.topMargin
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.leftMargin = (initialLeft + dx).coerceIn(0, (previewWidth - params.width).coerceAtLeast(0))
                    params.topMargin = (initialTop + dy).coerceIn(0, (previewHeight - params.height).coerceAtLeast(0))
                    rect.layoutParams = params
                    true
                }

                else -> false
            }
        }
    }

    private fun attachCropResizeListener(handle: View, rect: View, previewWidth: Int, previewHeight: Int) {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handle.setOnTouchListener { _, event ->
            val params = rect.layoutParams as FrameLayout.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val minSize = dp(MIN_CROP_SIZE_DP)
                    params.width = (initialWidth + dx).coerceIn(minSize, previewWidth - params.leftMargin)
                    params.height = (initialHeight + dy).coerceIn(minSize, previewHeight - params.topMargin)
                    rect.layoutParams = params
                    true
                }

                else -> false
            }
        }
    }

    private fun onCropConfirmed(fullResBitmap: Bitmap, previewScale: Float, left: Int, top: Int, width: Int, height: Int) {
        val scaleBack = 1f / previewScale
        val cropX = (left * scaleBack).toInt().coerceIn(0, fullResBitmap.width - 1)
        val cropY = (top * scaleBack).toInt().coerceIn(0, fullResBitmap.height - 1)
        val cropWidth = (width * scaleBack).toInt().coerceIn(1, fullResBitmap.width - cropX)
        val cropHeight = (height * scaleBack).toInt().coerceIn(1, fullResBitmap.height - cropY)

        val cropped = Bitmap.createBitmap(fullResBitmap, cropX, cropY, cropWidth, cropHeight)
        val downscaled = downscaleForUpload(cropped)
        if (downscaled !== cropped) cropped.recycle()

        pendingCaptureBitmap?.recycle()
        pendingCaptureBitmap = downscaled
        pendingCaptureIsDetailScan = true
        updatePendingThumbnailUi()

        removeCropOverlay(recycleSource = true)
    }

    private fun discardPendingCapture() {
        pendingCaptureBitmap?.recycle()
        pendingCaptureBitmap = null
        pendingCaptureIsDetailScan = false
        updatePendingThumbnailUi()
    }

    private fun updatePendingThumbnailUi() {
        val bitmap = pendingCaptureBitmap
        if (bitmap == null) {
            pendingThumbnailContainer?.visibility = View.GONE
            pendingThumbnailImageView?.setImageBitmap(null)
            pendingThumbnailLabelView?.visibility = View.GONE
        } else {
            pendingThumbnailImageView?.setImageBitmap(bitmap)
            pendingThumbnailContainer?.visibility = View.VISIBLE
            pendingThumbnailLabelView?.visibility = if (pendingCaptureIsDetailScan) View.VISIBLE else View.GONE
        }
    }

    // endregion

    // region Coach conversation

    private suspend fun requestCoachReply() {
        val apiKey = securePrefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            appendDisplayMessage("Coach", "No API key set. Open Settings to add one.", isError = true)
            return
        }

        showTypingIndicator()

        val systemPrompt = buildSystemPrompt(securePrefs.getBriefing().orEmpty())
        val result = AnthropicClient.sendMessage(apiKey, systemPrompt, history)

        hideTypingIndicator()

        result.onSuccess { reply ->
            appendHistory(ApiMessage(role = "assistant", blocks = listOf(ApiContentBlock(type = "text", text = reply))))
            val isWatching = reply.trim() == WATCHING_REPLY
            appendDisplayMessage("Coach", reply, isWatching = isWatching)
            if (!isExpanded && !isWatching) showReplyNotification(reply)
        }.onFailure { error ->
            appendDisplayMessage("Coach", error.message ?: "Something went wrong.", isError = true)
        }
    }

    private fun buildSystemPrompt(briefing: String): String =
        if (briefing.isBlank()) SYSTEM_PROMPT_PREFIX else "$SYSTEM_PROMPT_PREFIX\n\n$briefing"

    private fun appendHistory(message: ApiMessage) {
        history.add(message)
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeAt(0)
        }
        stripOldImageBlocks()
    }

    // Keeps image data only on the most recent messages so payload size/memory don't grow unbounded.
    private fun stripOldImageBlocks() {
        val keepImagesFrom = (history.size - RECENT_IMAGE_MESSAGE_COUNT).coerceAtLeast(0)
        for (i in 0 until keepImagesFrom) {
            val message = history[i]
            if (message.blocks.none { it.type == "image" }) continue

            history[i] = message.copy(
                blocks = message.blocks.map { block ->
                    if (block.type == "image") {
                        ApiContentBlock(type = "text", text = "[earlier screenshot]")
                    } else {
                        block
                    }
                }
            )
        }
    }

    private fun showTypingIndicator() {
        val entry = DisplayEntry(label = "Coach", text = "typing…", isTyping = true)
        typingEntry = entry
        displayMessages.add(entry)
        messageAdapter.notifyDataSetChanged()
        listViewRef?.setSelection(messageAdapter.count - 1)
    }

    private fun hideTypingIndicator() {
        typingEntry?.let { displayMessages.remove(it) }
        typingEntry = null
        messageAdapter.notifyDataSetChanged()
    }

    private fun appendDisplayMessage(
        label: String,
        text: String,
        thumbnail: Bitmap? = null,
        isWatching: Boolean = false,
        isError: Boolean = false,
        isAutoScreenshot: Boolean = false
    ) {
        val entry = DisplayEntry(label, text, thumbnail, isWatching = isWatching, isError = isError)
        displayMessages.add(entry)
        messageAdapter.notifyDataSetChanged()
        listViewRef?.setSelection(messageAdapter.count - 1)

        if (!isError) {
            persistEntry(entry, isAutoScreenshot)
        }
    }

    private fun persistEntry(entry: DisplayEntry, isAutoScreenshot: Boolean) {
        val role = if (entry.label == "You") "user" else "assistant"
        val thumbnail = entry.thumbnail
        serviceScope.launch(Dispatchers.IO) {
            val imagePath = thumbnail?.let { ThumbnailStorage.save(this@OverlayService, it, entry.timestamp) }
            chatDatabase.chatMessageDao().insert(
                ChatMessageEntity(
                    role = role,
                    text = entry.text,
                    imagePath = imagePath,
                    timestamp = entry.timestamp,
                    isAutoScreenshot = isAutoScreenshot
                )
            )
        }
    }

    // Only for chat-log continuity across restarts — the live API-bound `history` list still
    // starts empty each service instance and is capped/stripped exactly as before.
    private fun loadPersistedHistory() {
        serviceScope.launch {
            val entities = withContext(Dispatchers.IO) { chatDatabase.chatMessageDao().getAll() }
            if (entities.isEmpty()) return@launch

            val loaded = withContext(Dispatchers.IO) {
                entities.map { entity ->
                    DisplayEntry(
                        label = if (entity.role == "user") "You" else "Coach",
                        text = entity.text,
                        thumbnail = entity.imagePath?.let { ThumbnailStorage.load(it) },
                        timestamp = entity.timestamp,
                        isWatching = entity.role == "assistant" && entity.text.trim() == WATCHING_REPLY
                    )
                }
            }

            displayMessages.addAll(loaded)
            messageAdapter.notifyDataSetChanged()
            listViewRef?.setSelection(messageAdapter.count - 1)
        }
    }

    private fun onClearConversationClicked() {
        displayMessages.forEach { it.thumbnail?.recycle() }
        displayMessages.clear()
        history.clear()
        typingEntry = null
        messageAdapter.notifyDataSetChanged()

        serviceScope.launch(Dispatchers.IO) {
            chatDatabase.chatMessageDao().deleteAll()
            ThumbnailStorage.clearAll(this@OverlayService)
        }
    }

    // endregion

    // region Notifications

    private fun ensureForeground() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, currentForegroundServiceType())
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun currentForegroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (mediaProjectionTypeActive) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return type
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

    private inner class ChatAdapter : BaseAdapter() {
        override fun getCount(): Int = displayMessages.size
        override fun getItem(position: Int): DisplayEntry = displayMessages[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = displayMessages[position]
            val context = parent.context

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.argb(BUBBLE_ROW_BG_ALPHA, 255, 255, 255))
                }
            }

            entry.thumbnail?.let { thumbnail ->
                row.addView(
                    ImageView(context).apply {
                        setImageBitmap(thumbnail)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(THUMBNAIL_SIZE_DP), dp(THUMBNAIL_SIZE_DP)).apply {
                            marginEnd = dp(8)
                        }
                    }
                )
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textColumn.addView(
                TextView(context).apply {
                    text = if (entry.isTyping) "${entry.label} is typing…" else "${entry.label}: ${entry.text}"
                    when {
                        entry.isError -> setTextColor(Color.rgb(255, 138, 128))
                        entry.isTyping -> setTextColor(Color.rgb(200, 200, 205))
                        entry.isWatching -> {
                            setTextColor(Color.rgb(200, 200, 205))
                            textSize = 12f
                        }
                        else -> setTextColor(Color.WHITE)
                    }
                }
            )

            if (!entry.isTyping) {
                textColumn.addView(
                    TextView(context).apply {
                        text = timeFormatter.format(Date(entry.timestamp))
                        textSize = 10f
                        setTextColor(Color.rgb(180, 180, 185))
                    }
                )
            }

            row.addView(textColumn)
            return row
        }
    }
}

private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
