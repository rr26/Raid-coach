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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    private class TopicState {
        val displayMessages = mutableListOf<DisplayEntry>()
        val history = mutableListOf<ApiMessage>()
        var typingEntry: DisplayEntry? = null
        var pinnedSummary: String? = null
        val goals = mutableListOf<GoalEntity>()
        var isLoaded = false
    }

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

        private const val WEB_SEARCH_CLAUSE = "You have web search. When asked about a champion's skills, " +
            "ratings, or recommended builds, search for current guides (e.g. 'HellHades [champion name]', " +
            "'ayumilove [champion name]') to verify skills and find the most-used meta builds before advising. " +
            "Combine that with my roster and inventory when recommending. Don't search for questions you can " +
            "answer from the screenshot or conversation alone."

        private const val CHAMPION_CACHE_INSTRUCTION = "When your answer includes newly web-searched " +
            "information about a specific champion's skills, ratings, or build, end your reply on its own new " +
            "line with exactly: [CHAMPION_CACHE: <Champion Name> | <one-sentence summary of their key " +
            "skills/build>]. Omit this line entirely if you didn't search, or if the answer isn't about a " +
            "specific champion's skills/build."

        private val CHAMPION_CACHE_REGEX = Regex("""\[CHAMPION_CACHE:\s*([^|]+)\|\s*(.+?)]""")

        private val DEFAULT_TOPICS = listOf(
            "General", "Clan Boss", "Hydra", "Fire Knight", "Dragon", "Spider", "Ice Golem", "Arena"
        )

        private const val SUMMARY_MAX_TOKENS = 220
        private const val SUMMARY_SYSTEM_PROMPT_PREFIX = "You are maintaining a running summary of the best " +
            "current setup discussed for a specific Raid: Shadow Legends dungeon/mode, based only on the " +
            "conversation so far. Write ONLY a concise 3-5 line summary: team composition, key stats/gear, and " +
            "main tips. No preamble, no markdown, just the summary lines. Dungeon/mode: "

        private const val GOAL_PLAN_MAX_TOKENS = 500
        private const val GOAL_PLAN_SYSTEM_SUFFIX = "You are helping plan a specific in-game goal for a Raid: " +
            "Shadow Legends account, using the ACCOUNT STATE above. Given my ACCOUNT STATE, create a " +
            "step-by-step plan for this goal: concrete milestones, which champions/gear to develop first, and " +
            "what to check next session. Reply with ONLY a numbered list of steps, max 10. No preamble, no " +
            "extra text."

        private const val DAILY_PRIORITY_MAX_TOKENS = 350
        private const val DAILY_PRIORITY_SYSTEM_SUFFIX = "Using the ACCOUNT STATE and active goals above, " +
            "recommend the top 3 most valuable actions for today's play session, in priority order. Be " +
            "specific: which dungeon stage, which champion to level/gear, which event to push, and why. Keep " +
            "each point to one sentence. No preamble, no extra text."
        private const val DAILY_PRIORITY_USER_MESSAGE = "What should I do today?"
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

    private var tabBarRowView: LinearLayout? = null
    private var pinnedSummaryContainer: View? = null
    private var pinnedSummaryHeaderView: TextView? = null
    private var pinnedSummaryBodyView: TextView? = null
    private var pinnedSummaryExpanded = true
    private var topicOverlayView: View? = null
    private var goalOverlayView: View? = null

    private var goalsRowContainer: View? = null
    private var goalsRowView: LinearLayout? = null

    private var dailyPriorityContainer: View? = null
    private var dailyPriorityBodyView: TextView? = null
    private var dailyPriorityText: String? = null
    private var isDailyPriorityLoading = false

    private val topics = mutableListOf<String>()
    private var currentTopic = DEFAULT_TOPICS.first()
    private val topicStates = mutableMapOf<String, TopicState>()

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

    private lateinit var messageAdapter: ChatAdapter
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
        loadTopics()
        loadDailyPriority()
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
        topicStates.values.forEach { state -> state.displayMessages.forEach { it.thumbnail?.recycle() } }

        runCatching { windowManager.removeView(bubbleView) }
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        scanChoiceView?.let { view -> runCatching { windowManager.removeView(view) } }
        cropOverlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        topicOverlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        goalOverlayView?.let { view -> runCatching { windowManager.removeView(view) } }
    }

    // region Topics

    private fun topicState(topic: String): TopicState = topicStates.getOrPut(topic) { TopicState() }

    private fun loadTopics() {
        serviceScope.launch {
            val entities = withContext(Dispatchers.IO) { chatDatabase.topicDao().getAll() }
            if (entities.isEmpty()) {
                val seeded = DEFAULT_TOPICS.mapIndexed { index, name -> TopicEntity(name, index) }
                withContext(Dispatchers.IO) { chatDatabase.topicDao().insertAll(seeded) }
                topics.addAll(DEFAULT_TOPICS)
            } else {
                topics.addAll(entities.sortedBy { it.sortOrder }.map { it.name })
            }

            val savedTopic = panelLayoutPrefs.getActiveTopic(topics.first())
            currentTopic = if (topics.contains(savedTopic)) savedTopic else topics.first()

            ensureTopicLoaded(currentTopic)
            rebuildTabBar()
        }
    }

    // Loads a topic's persisted chat log and pinned summary from Room the first time it's visited.
    private fun ensureTopicLoaded(topic: String) {
        val state = topicState(topic)
        if (state.isLoaded) return
        state.isLoaded = true

        serviceScope.launch {
            val entities = withContext(Dispatchers.IO) { chatDatabase.chatMessageDao().getByTopic(topic) }
            if (entities.isNotEmpty()) {
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
                state.displayMessages.addAll(0, loaded)
                if (topic == currentTopic) {
                    messageAdapter.notifyDataSetChanged()
                    listViewRef?.setSelection(messageAdapter.count - 1)
                }
            }

            val summaryEntity = withContext(Dispatchers.IO) { chatDatabase.topicSummaryDao().get(topic) }
            state.pinnedSummary = summaryEntity?.summary
            if (topic == currentTopic) updatePinnedSummaryUi()

            val goals = withContext(Dispatchers.IO) { chatDatabase.goalDao().getByTopic(topic) }
            state.goals.clear()
            state.goals.addAll(goals)
            if (topic == currentTopic) rebuildGoalsRow()
        }
    }

    private fun switchToTopic(topic: String) {
        if (topic == currentTopic) return
        currentTopic = topic
        panelLayoutPrefs.setActiveTopic(topic)
        ensureTopicLoaded(topic)
        rebuildTabBar()
        messageAdapter.notifyDataSetChanged()
        listViewRef?.setSelection((messageAdapter.count - 1).coerceAtLeast(0))
        updatePinnedSummaryUi()
        rebuildGoalsRow()
        updateDailyPriorityUi()
    }

    private fun createTopic(name: String) {
        if (topics.contains(name)) {
            switchToTopic(name)
            return
        }
        topics.add(name)
        serviceScope.launch(Dispatchers.IO) {
            chatDatabase.topicDao().insert(TopicEntity(name, topics.size - 1))
        }
        switchToTopic(name)
        rebuildTabBar()
    }

    private fun renameTopic(oldName: String, newName: String) {
        if (oldName == newName || topics.contains(newName)) return

        val index = topics.indexOf(oldName)
        if (index == -1) return
        topics[index] = newName

        topicStates.remove(oldName)?.let { topicStates[newName] = it }
        if (currentTopic == oldName) {
            currentTopic = newName
            panelLayoutPrefs.setActiveTopic(newName)
        }

        serviceScope.launch(Dispatchers.IO) {
            chatDatabase.chatMessageDao().renameTopic(oldName, newName)
            chatDatabase.topicSummaryDao().renameTopic(oldName, newName)
            chatDatabase.savedTeamDao().renameTopic(oldName, newName)
            chatDatabase.goalDao().renameTopic(oldName, newName)
            chatDatabase.topicDao().rename(oldName, newName)
        }

        rebuildTabBar()
        updatePinnedSummaryUi()
        rebuildGoalsRow()
    }

    private fun deleteTopic(topic: String) {
        if (topics.size <= 1) return

        val index = topics.indexOf(topic)
        if (index == -1) return
        topics.removeAt(index)
        topicStates.remove(topic)?.displayMessages?.forEach { it.thumbnail?.recycle() }

        if (currentTopic == topic) {
            currentTopic = topics.getOrElse(index) { topics.first() }
            panelLayoutPrefs.setActiveTopic(currentTopic)
            ensureTopicLoaded(currentTopic)
        }

        serviceScope.launch(Dispatchers.IO) {
            val entities = chatDatabase.chatMessageDao().getByTopic(topic)
            entities.forEach { entity -> entity.imagePath?.let { ThumbnailStorage.delete(it) } }
            chatDatabase.chatMessageDao().deleteByTopic(topic)
            chatDatabase.topicSummaryDao().delete(topic)
            chatDatabase.savedTeamDao().delete(topic)
            chatDatabase.goalDao().deleteByTopic(topic)
            chatDatabase.topicDao().delete(topic)
        }

        rebuildTabBar()
        messageAdapter.notifyDataSetChanged()
        updatePinnedSummaryUi()
        rebuildGoalsRow()
    }

    private fun createTabBarView(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabBarRowView = row
        rebuildTabBar()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun createGoalsRowView(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        goalsRowView = row
        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        goalsRowContainer = scrollView
        rebuildGoalsRow()
        return scrollView
    }

    private fun rebuildTabBar() {
        val row = tabBarRowView ?: return
        row.removeAllViews()

        for (topic in topics) {
            val isActive = topic == currentTopic
            val chip = TextView(this).apply {
                text = topic
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(if (isActive) Color.WHITE else Color.rgb(180, 180, 185))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(
                        if (isActive) Color.argb(220, 33, 150, 243) else Color.argb(120, 90, 90, 96)
                    )
                }
                setOnClickListener { switchToTopic(topic) }
                setOnLongClickListener {
                    showTopicOptionsOverlay(topic)
                    true
                }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }

        val addChip = TextView(this).apply {
            text = "+"
            textSize = 14f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(160, 120, 120, 120))
            }
            setOnClickListener { showCreateTopicOverlay() }
        }
        row.addView(addChip)
    }

    private fun removeTopicOverlay() {
        topicOverlayView?.let { runCatching { windowManager.removeView(it) } }
        topicOverlayView = null
    }

    private fun showTopicOptionsOverlay(topic: String) {
        removeTopicOverlay()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(235, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
        }

        val title = TextView(this).apply {
            text = topic
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val renameButton = Button(this).apply {
            text = "Rename"
            setOnClickListener {
                removeTopicOverlay()
                showTopicNameEntryOverlay(topic) { newName -> renameTopic(topic, newName) }
            }
        }

        val deleteButton = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                removeTopicOverlay()
                deleteTopic(topic)
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { removeTopicOverlay() }
        }

        card.addView(title)
        card.addView(renameButton)
        card.addView(deleteButton)
        card.addView(cancelButton)

        topicOverlayView = card
        windowManager.addView(card, createScanChoiceParams())
    }

    private fun showCreateTopicOverlay() {
        showTopicNameEntryOverlay(null) { newName -> createTopic(newName) }
    }

    private fun showTopicNameEntryOverlay(existingName: String?, onConfirm: (String) -> Unit) {
        removeTopicOverlay()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(235, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
        }

        val title = TextView(this).apply {
            text = if (existingName != null) "Rename tab" else "New tab"
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val input = EditText(this).apply {
            setText(existingName.orEmpty())
            hint = "Tab name"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(180, 180, 185))
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    removeTopicOverlay()
                    onConfirm(name)
                }
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { removeTopicOverlay() }
        }

        card.addView(title)
        card.addView(input)
        card.addView(saveButton)
        card.addView(cancelButton)

        topicOverlayView = card
        windowManager.addView(card, createNameEntryParams())
    }

    private fun createNameEntryParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    // endregion

    // region Goals

    private fun rebuildGoalsRow() {
        val row = goalsRowView ?: return
        row.removeAllViews()

        val activeGoals = topicState(currentTopic).goals.filter { it.status == GoalStatus.ACTIVE }
        goalsRowContainer?.visibility = if (activeGoals.isEmpty()) View.GONE else View.VISIBLE

        for (goal in activeGoals) {
            val steps = GoalPlanCodec.decode(goal.plan)
            val progress = if (steps.isEmpty()) "" else " (${steps.count { it.done }}/${steps.size})"
            val chip = TextView(this).apply {
                text = "🎯 ${goal.title}$progress"
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(200, 123, 31, 162))
                }
                setOnClickListener { showGoalDetailOverlay(currentTopic, goal) }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }

        val addChip = TextView(this).apply {
            text = "+ Goal"
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(160, 120, 120, 120))
            }
            setOnClickListener { showCreateGoalOverlay() }
        }
        row.addView(addChip)
    }

    private fun removeGoalOverlay() {
        goalOverlayView?.let { runCatching { windowManager.removeView(it) } }
        goalOverlayView = null
    }

    private fun showCreateGoalOverlay() {
        removeGoalOverlay()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(235, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
        }

        val title = TextView(this).apply {
            text = "New goal for $currentTopic"
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val input = EditText(this).apply {
            hint = "e.g. 2-key UNM Clan Boss"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(180, 180, 185))
        }

        val saveButton = Button(this).apply {
            text = "Create"
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    removeGoalOverlay()
                    createGoal(currentTopic, name)
                }
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { removeGoalOverlay() }
        }

        card.addView(title)
        card.addView(input)
        card.addView(saveButton)
        card.addView(cancelButton)

        goalOverlayView = card
        windowManager.addView(card, createNameEntryParams())
    }

    private fun createGoal(topic: String, title: String) {
        serviceScope.launch {
            val newGoal = GoalEntity(title = title, topic = topic, createdAt = System.currentTimeMillis())
            val id = withContext(Dispatchers.IO) { chatDatabase.goalDao().insert(newGoal) }
            val goal = newGoal.copy(id = id)
            topicState(topic).goals.add(goal)
            if (topic == currentTopic) rebuildGoalsRow()
            requestGoalPlan(topic, goal)
        }
    }

    // Silent, cheap request: given the ACCOUNT STATE, ask for a concrete step-by-step plan for a
    // freshly created goal. The reply is parsed into a checklist and never shown in the chat log.
    private fun requestGoalPlan(topic: String, goal: GoalEntity) {
        serviceScope.launch {
            val apiKey = securePrefs.getApiKey()
            if (apiKey.isNullOrBlank()) return@launch

            val accountState = AccountStateBuilder.build(chatDatabase, topic)
            val systemPrompt = "$accountState\n\n$GOAL_PLAN_SYSTEM_SUFFIX"
            val userMessage = ApiMessage(role = "user", blocks = listOf(ApiContentBlock(type = "text", text = "Goal: ${goal.title}")))

            val result = AnthropicClient.sendMessage(
                apiKey, systemPrompt, listOf(userMessage), webSearchEnabled = false, maxTokens = GOAL_PLAN_MAX_TOKENS
            )

            result.onSuccess { reply ->
                val steps = GoalPlanCodec.parsePlanText(reply.text)
                if (steps.isEmpty()) return@onSuccess
                val updated = goal.copy(plan = GoalPlanCodec.encode(steps))
                withContext(Dispatchers.IO) { chatDatabase.goalDao().update(updated) }
                replaceGoalInState(topic, updated)
            }
        }
    }

    private fun replaceGoalInState(topic: String, goal: GoalEntity) {
        val state = topicState(topic)
        val index = state.goals.indexOfFirst { it.id == goal.id }
        if (index != -1) state.goals[index] = goal else state.goals.add(goal)
        if (topic == currentTopic) rebuildGoalsRow()
    }

    private fun toggleGoalStep(topic: String, goal: GoalEntity, stepIndex: Int) {
        val steps = GoalPlanCodec.decode(goal.plan).toMutableList()
        if (stepIndex !in steps.indices) return
        steps[stepIndex] = steps[stepIndex].copy(done = !steps[stepIndex].done)
        val updated = goal.copy(plan = GoalPlanCodec.encode(steps))
        replaceGoalInState(topic, updated)
        serviceScope.launch(Dispatchers.IO) { chatDatabase.goalDao().update(updated) }
    }

    private fun setGoalStatus(topic: String, goal: GoalEntity, status: String) {
        val updated = goal.copy(status = status)
        replaceGoalInState(topic, updated)
        serviceScope.launch(Dispatchers.IO) { chatDatabase.goalDao().update(updated) }
    }

    private fun deleteGoal(topic: String, goal: GoalEntity) {
        topicState(topic).goals.removeAll { it.id == goal.id }
        if (topic == currentTopic) rebuildGoalsRow()
        serviceScope.launch(Dispatchers.IO) { chatDatabase.goalDao().delete(goal.id) }
    }

    private fun showGoalDetailOverlay(topic: String, goal: GoalEntity) {
        removeGoalOverlay()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(235, PANEL_BG_R, PANEL_BG_G, PANEL_BG_B))
            }
        }

        val title = TextView(this).apply {
            text = goal.title
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        card.addView(title)

        val steps = GoalPlanCodec.decode(goal.plan)
        if (steps.isEmpty()) {
            card.addView(
                TextView(this).apply {
                    text = "Planning…"
                    setTextColor(Color.rgb(200, 200, 205))
                    textSize = 12f
                }
            )
        } else {
            steps.forEachIndexed { index, step ->
                card.addView(
                    CheckBox(this).apply {
                        text = step.text
                        isChecked = step.done
                        setTextColor(Color.WHITE)
                        setOnCheckedChangeListener { _, _ -> toggleGoalStep(topic, goal, index) }
                    }
                )
            }
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val pauseResumeButton = Button(this).apply {
            text = if (goal.status == GoalStatus.PAUSED) "Resume" else "Pause"
            setOnClickListener {
                val newStatus = if (goal.status == GoalStatus.PAUSED) GoalStatus.ACTIVE else GoalStatus.PAUSED
                setGoalStatus(topic, goal, newStatus)
                removeGoalOverlay()
            }
        }

        val doneButton = Button(this).apply {
            text = "Mark done"
            setOnClickListener {
                setGoalStatus(topic, goal, GoalStatus.DONE)
                removeGoalOverlay()
            }
        }

        val deleteButton = Button(this).apply {
            text = "Delete"
            setOnClickListener {
                deleteGoal(topic, goal)
                removeGoalOverlay()
            }
        }

        statusRow.addView(pauseResumeButton)
        statusRow.addView(doneButton)
        statusRow.addView(deleteButton)
        card.addView(statusRow)

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { removeGoalOverlay() }
        }
        card.addView(closeButton)

        goalOverlayView = card
        windowManager.addView(card, createScanChoiceParams())
    }

    // endregion

    // region Daily priority

    private fun loadDailyPriority() {
        serviceScope.launch {
            val entity = withContext(Dispatchers.IO) { chatDatabase.dailyPriorityDao().get() }
            dailyPriorityText = entity?.text
            updateDailyPriorityUi()
        }
    }

    private fun updateDailyPriorityUi() {
        val showSection = currentTopic == "General"
        dailyPriorityContainer?.visibility = if (showSection) View.VISIBLE else View.GONE
        if (!showSection) return

        val text = dailyPriorityText
        if (isDailyPriorityLoading) {
            dailyPriorityBodyView?.text = "Thinking…"
            dailyPriorityBodyView?.visibility = View.VISIBLE
        } else if (!text.isNullOrBlank()) {
            dailyPriorityBodyView?.text = text
            dailyPriorityBodyView?.visibility = View.VISIBLE
        } else {
            dailyPriorityBodyView?.visibility = View.GONE
        }
    }

    private fun onDailyPriorityButtonClicked() {
        if (isDailyPriorityLoading) return
        isDailyPriorityLoading = true
        updateDailyPriorityUi()

        serviceScope.launch {
            val apiKey = securePrefs.getApiKey()
            if (apiKey.isNullOrBlank()) {
                isDailyPriorityLoading = false
                dailyPriorityText = "No API key set. Open Settings to add one."
                updateDailyPriorityUi()
                return@launch
            }

            val accountState = AccountStateBuilder.build(chatDatabase, topic = null)
            val systemPrompt = "$accountState\n\n$DAILY_PRIORITY_SYSTEM_SUFFIX"
            val userMessage = ApiMessage(role = "user", blocks = listOf(ApiContentBlock(type = "text", text = DAILY_PRIORITY_USER_MESSAGE)))

            val result = AnthropicClient.sendMessage(
                apiKey, systemPrompt, listOf(userMessage), webSearchEnabled = false, maxTokens = DAILY_PRIORITY_MAX_TOKENS
            )

            isDailyPriorityLoading = false
            result.onSuccess { reply ->
                val text = reply.text.trim()
                dailyPriorityText = text
                updateDailyPriorityUi()
                withContext(Dispatchers.IO) {
                    chatDatabase.dailyPriorityDao().upsert(DailyPriorityEntity(0, text, System.currentTimeMillis()))
                }
            }.onFailure { error ->
                dailyPriorityText = error.message ?: "Something went wrong."
                updateDailyPriorityUi()
            }
        }
    }

    // endregion

    // region Pinned summary

    private fun updatePinnedSummaryUi() {
        val summary = topicState(currentTopic).pinnedSummary
        if (summary.isNullOrBlank()) {
            pinnedSummaryContainer?.visibility = View.GONE
            return
        }
        pinnedSummaryContainer?.visibility = View.VISIBLE
        pinnedSummaryHeaderView?.text = "📌 $currentTopic setup ${if (pinnedSummaryExpanded) "▾" else "▸"}"
        pinnedSummaryBodyView?.text = summary
        pinnedSummaryBodyView?.visibility = if (pinnedSummaryExpanded) View.VISIBLE else View.GONE
    }

    // Silent, cheap, text-only follow-up request that keeps a short "current best setup" summary
    // for this topic up to date. Never shown in the chat log itself, and failures are ignored.
    private fun refreshPinnedSummary(topic: String) {
        serviceScope.launch {
            val apiKey = securePrefs.getApiKey()
            if (apiKey.isNullOrBlank()) return@launch

            val strippedHistory = stripImagesForSummary(topicState(topic).history)
            if (strippedHistory.isEmpty()) return@launch

            val systemPrompt = SUMMARY_SYSTEM_PROMPT_PREFIX + topic
            val result = AnthropicClient.sendMessage(
                apiKey, systemPrompt, strippedHistory, webSearchEnabled = false, maxTokens = SUMMARY_MAX_TOKENS
            )

            result.onSuccess { reply ->
                val summary = reply.text.trim()
                if (summary.isEmpty()) return@onSuccess

                topicState(topic).pinnedSummary = summary
                if (topic == currentTopic) updatePinnedSummaryUi()

                withContext(Dispatchers.IO) {
                    chatDatabase.topicSummaryDao().upsert(
                        TopicSummaryEntity(topic, summary, System.currentTimeMillis())
                    )
                }
            }
        }
    }

    private fun stripImagesForSummary(history: List<ApiMessage>): List<ApiMessage> {
        return history.mapNotNull { message ->
            val textBlocks = message.blocks.filter { it.type == "text" }
            if (textBlocks.isEmpty()) null else message.copy(blocks = textBlocks)
        }
    }

    // endregion

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
        appendDisplayMessage(currentTopic, "Coach", "Screen capture permission was not granted.", isError = true)
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
        val topic = currentTopic
        val bitmap = captureWithOverlayHidden() ?: return

        val previous = lastAutoFrame
        if (previous != null && isNearlyIdentical(previous, bitmap)) {
            bitmap.recycle()
            return
        }

        previous?.recycle()
        lastAutoFrame = bitmap
        sendCapturedFrame(topic, bitmap, "Auto-screenshot sent", isAutoScreenshot = true)
    }

    private fun performImmediateCapture() {
        val topic = currentTopic
        serviceScope.launch {
            val bitmap = captureWithOverlayHidden()
            if (bitmap == null) {
                appendDisplayMessage(topic, "Coach", "Couldn't capture the screen. Try again.", isError = true)
                return@launch
            }
            sendCapturedFrame(topic, bitmap, "[Screenshot]", isAutoScreenshot = false)
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

    private suspend fun sendCapturedFrame(topic: String, bitmap: Bitmap, label: String, isAutoScreenshot: Boolean) {
        val thumbnail = Bitmap.createScaledBitmap(bitmap, dp(THUMBNAIL_SIZE_DP), dp(THUMBNAIL_SIZE_DP), true)
        appendDisplayMessage(topic, "You", label, thumbnail, isAutoScreenshot = isAutoScreenshot)

        val scaled = downscaleForUpload(bitmap)
        val base64 = withContext(Dispatchers.Default) { bitmapToJpegBase64(scaled) }
        if (scaled !== bitmap) scaled.recycle()

        appendHistory(topic, ApiMessage(role = "user", blocks = listOf(ApiContentBlock(type = "image", imageBase64 = base64))))
        requestCoachReply(topic)
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

        val dailyPriorityButton = Button(this).apply {
            text = "What should I do today?"
            setOnClickListener { onDailyPriorityButtonClicked() }
        }

        val dailyPriorityBody = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }
        dailyPriorityBodyView = dailyPriorityBody

        val dailyPriorityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(90, 33, 150, 243))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(dailyPriorityBody)
        }

        val dailyPrioritySection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(dailyPriorityButton)
            addView(dailyPriorityCard)
        }
        dailyPriorityContainer = dailyPrioritySection

        val pinnedSummaryHeader = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        pinnedSummaryHeaderView = pinnedSummaryHeader

        val pinnedSummaryBody = TextView(this).apply {
            setTextColor(Color.rgb(220, 220, 225))
            textSize = 11f
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        pinnedSummaryBodyView = pinnedSummaryBody

        val pinnedSummaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(90, 255, 200, 0))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(pinnedSummaryHeader)
            addView(pinnedSummaryBody)
            setOnClickListener {
                pinnedSummaryExpanded = !pinnedSummaryExpanded
                updatePinnedSummaryUi()
            }
            visibility = View.GONE
        }
        pinnedSummaryContainer = pinnedSummaryCard

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
                onSendManualMessage(currentTopic, text, attachedBitmap, isDetailScan)
            }
        }

        inputRow.addView(cameraButton)
        inputRow.addView(input)
        inputRow.addView(sendButton)

        content.addView(header)
        content.addView(createTabBarView())
        content.addView(dailyPrioritySection)
        content.addView(createGoalsRowView())
        content.addView(pinnedSummaryCard)
        content.addView(opacityRow)
        content.addView(controlsRow)
        content.addView(listView)
        content.addView(pendingThumbnailRow)
        content.addView(inputRow)

        updatePinnedSummaryUi()
        updateDailyPriorityUi()

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

    private fun onSendManualMessage(topic: String, text: String, imageBitmap: Bitmap?, isDetailScan: Boolean) {
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
        appendDisplayMessage(topic, "You", label, thumbnail)
        appendHistory(topic, ApiMessage(role = "user", blocks = blocks))
        imageBitmap?.recycle()

        serviceScope.launch { requestCoachReply(topic) }
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
                    appendDisplayMessage(currentTopic, "Coach", "Couldn't capture the screen. Try again.", isError = true)
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
                    appendDisplayMessage(currentTopic, "Coach", "Couldn't capture the screen. Try again.", isError = true)
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

    private suspend fun requestCoachReply(topic: String) {
        val apiKey = securePrefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            appendDisplayMessage(topic, "Coach", "No API key set. Open Settings to add one.", isError = true)
            return
        }

        showTypingIndicator(topic)

        val webSearchEnabled = securePrefs.getWebSearchEnabled()
        val accountState = AccountStateBuilder.build(chatDatabase, topic)
        val systemPrompt = buildSystemPrompt(securePrefs.getBriefing().orEmpty(), webSearchEnabled, accountState, topic)
        val state = topicState(topic)
        val result = AnthropicClient.sendMessage(apiKey, systemPrompt, state.history, webSearchEnabled)

        hideTypingIndicator(topic)

        result.onSuccess { reply ->
            val (displayText, championCache) = extractChampionCache(reply.text)
            appendHistory(topic, ApiMessage(role = "assistant", blocks = listOf(ApiContentBlock(type = "text", text = displayText))))
            val isWatching = displayText.trim() == WATCHING_REPLY
            appendDisplayMessage(topic, "Coach", displayText, isWatching = isWatching, usedWebSearch = reply.usedWebSearch)
            if (!isExpanded && !isWatching) showReplyNotification(displayText)

            championCache?.let { (name, summary) ->
                serviceScope.launch(Dispatchers.IO) {
                    chatDatabase.championCacheDao().upsert(
                        ChampionCacheEntity(name, summary, System.currentTimeMillis())
                    )
                }
            }

            if (!isWatching) {
                refreshPinnedSummary(topic)
            }
        }.onFailure { error ->
            appendDisplayMessage(topic, "Coach", error.message ?: "Something went wrong.", isError = true)
        }
    }

    private fun buildSystemPrompt(briefing: String, webSearchEnabled: Boolean, accountState: String, topic: String): String {
        val parts = mutableListOf(accountState, SYSTEM_PROMPT_PREFIX, "Current tab/focus: $topic.")
        if (webSearchEnabled) {
            parts.add(WEB_SEARCH_CLAUSE)
            parts.add(CHAMPION_CACHE_INSTRUCTION)
        }
        if (briefing.isNotBlank()) parts.add(briefing)
        return parts.joinToString("\n\n")
    }

    // Strips the model's own [CHAMPION_CACHE: ...] marker (if present) from the display text and
    // returns it separately so it can be stored, without ever showing the raw marker to the user.
    private fun extractChampionCache(rawText: String): Pair<String, Pair<String, String>?> {
        val match = CHAMPION_CACHE_REGEX.find(rawText) ?: return rawText to null
        val name = match.groupValues[1].trim()
        val summary = match.groupValues[2].trim()
        val cleaned = rawText.replace(match.value, "").trim()
        return cleaned to (name to summary)
    }

    private fun appendHistory(topic: String, message: ApiMessage) {
        val state = topicState(topic)
        state.history.add(message)
        while (state.history.size > MAX_HISTORY_MESSAGES) {
            state.history.removeAt(0)
        }
        stripOldImageBlocks(state.history)
    }

    // Keeps image data only on the most recent messages so payload size/memory don't grow unbounded.
    private fun stripOldImageBlocks(history: MutableList<ApiMessage>) {
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

    private fun showTypingIndicator(topic: String) {
        val state = topicState(topic)
        val entry = DisplayEntry(label = "Coach", text = "typing…", isTyping = true)
        state.typingEntry = entry
        state.displayMessages.add(entry)
        if (topic == currentTopic) {
            messageAdapter.notifyDataSetChanged()
            listViewRef?.setSelection(messageAdapter.count - 1)
        }
    }

    private fun hideTypingIndicator(topic: String) {
        val state = topicState(topic)
        state.typingEntry?.let { state.displayMessages.remove(it) }
        state.typingEntry = null
        if (topic == currentTopic) {
            messageAdapter.notifyDataSetChanged()
        }
    }

    private fun appendDisplayMessage(
        topic: String,
        label: String,
        text: String,
        thumbnail: Bitmap? = null,
        isWatching: Boolean = false,
        isError: Boolean = false,
        isAutoScreenshot: Boolean = false,
        usedWebSearch: Boolean = false
    ) {
        val entry = DisplayEntry(label, text, thumbnail, isWatching = isWatching, isError = isError, usedWebSearch = usedWebSearch)
        val state = topicState(topic)
        state.displayMessages.add(entry)
        if (topic == currentTopic) {
            messageAdapter.notifyDataSetChanged()
            listViewRef?.setSelection(messageAdapter.count - 1)
        }

        if (!isError) {
            persistEntry(topic, entry, isAutoScreenshot)
        }
    }

    private fun persistEntry(topic: String, entry: DisplayEntry, isAutoScreenshot: Boolean) {
        val role = if (entry.label == "You") "user" else "assistant"
        val thumbnail = entry.thumbnail
        serviceScope.launch(Dispatchers.IO) {
            val imagePath = thumbnail?.let { ThumbnailStorage.save(this@OverlayService, it, entry.timestamp) }
            chatDatabase.chatMessageDao().insert(
                ChatMessageEntity(
                    topic = topic,
                    role = role,
                    text = entry.text,
                    imagePath = imagePath,
                    timestamp = entry.timestamp,
                    isAutoScreenshot = isAutoScreenshot
                )
            )
        }
    }

    private fun onClearConversationClicked() {
        val topic = currentTopic
        val state = topicState(topic)
        state.displayMessages.forEach { it.thumbnail?.recycle() }
        state.displayMessages.clear()
        state.history.clear()
        state.typingEntry = null
        state.pinnedSummary = null
        messageAdapter.notifyDataSetChanged()
        updatePinnedSummaryUi()

        serviceScope.launch(Dispatchers.IO) {
            val entities = chatDatabase.chatMessageDao().getByTopic(topic)
            entities.forEach { entity -> entity.imagePath?.let { ThumbnailStorage.delete(it) } }
            chatDatabase.chatMessageDao().deleteByTopic(topic)
            chatDatabase.topicSummaryDao().delete(topic)
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
        private fun items(): List<DisplayEntry> = topicState(currentTopic).displayMessages

        override fun getCount(): Int = items().size
        override fun getItem(position: Int): DisplayEntry = items()[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = items()[position]
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

            if (entry.usedWebSearch) {
                textColumn.addView(
                    TextView(context).apply {
                        text = "🔎 searched the web"
                        textSize = 10f
                        setTextColor(Color.rgb(180, 180, 185))
                    }
                )
            }

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
