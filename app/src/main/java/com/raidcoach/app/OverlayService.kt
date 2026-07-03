package com.raidcoach.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.raidcoach.app.action.STOP_OVERLAY"
        private const val NOTIFICATION_CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val BUBBLE_SIZE_DP = 56
        private const val PANEL_WIDTH_FRACTION = 0.4f
    }

    private val overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isExpanded = false

    private val messages = mutableListOf<String>()
    private lateinit var messageAdapter: ArrayAdapter<String>

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        messageAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messages)

        val bubbleSizePx = dp(BUBBLE_SIZE_DP)
        bubbleView = createBubbleView(bubbleSizePx)
        bubbleParams = createBubbleParams(bubbleSizePx)
        attachBubbleTouchListener()

        windowManager.addView(bubbleView, bubbleParams)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { windowManager.removeView(bubbleView) }
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
    }

    // region Bubble

    private fun createBubbleView(sizePx: Int): View {
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(180, 33, 150, 243))
            }
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }
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

    private fun attachBubbleTouchListener() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubbleView.setOnTouchListener { _, event ->
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

        val collapseButton = Button(this).apply {
            text = "Collapse"
            setOnClickListener { collapseOverlay() }
        }

        header.addView(title)
        header.addView(collapseButton)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            adapter = messageAdapter
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val input = EditText(this).apply {
            hint = "Message"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    messages.add(text)
                    messageAdapter.notifyDataSetChanged()
                    listView.setSelection(messageAdapter.count - 1)
                    input.text.clear()
                }
            }
        }

        inputRow.addView(input)
        inputRow.addView(sendButton)

        root.addView(header)
        root.addView(listView)
        root.addView(inputRow)

        return root
    }

    // endregion

    // region Notification

    private fun buildNotification(): Notification {
        createNotificationChannelIfNeeded()

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Raid Coach overlay")
            .setContentText("Overlay bubble is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop overlay", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Overlay service",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    // endregion

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
