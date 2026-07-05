package com.raidcoach.app

import android.content.Context
import android.content.SharedPreferences

class PanelLayoutPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    fun getX(default: Int): Int = prefs.getInt(KEY_X, default)
    fun getY(default: Int): Int = prefs.getInt(KEY_Y, default)
    fun getWidth(default: Int): Int = prefs.getInt(KEY_WIDTH, default)
    fun getHeight(default: Int): Int = prefs.getInt(KEY_HEIGHT, default)

    fun save(x: Int, y: Int, width: Int, height: Int) {
        prefs.edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .putInt(KEY_WIDTH, width)
            .putInt(KEY_HEIGHT, height)
            .apply()
    }

    fun getOpacityPercent(default: Int): Int = prefs.getInt(KEY_OPACITY, default)

    fun setOpacityPercent(value: Int) {
        prefs.edit().putInt(KEY_OPACITY, value).apply()
    }

    fun getActiveTopic(default: String): String = prefs.getString(KEY_ACTIVE_TOPIC, default) ?: default

    fun setActiveTopic(value: String) {
        prefs.edit().putString(KEY_ACTIVE_TOPIC, value).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "panel_layout_prefs"
        private const val KEY_X = "panel_x"
        private const val KEY_Y = "panel_y"
        private const val KEY_WIDTH = "panel_width"
        private const val KEY_HEIGHT = "panel_height"
        private const val KEY_OPACITY = "panel_opacity_percent"
        private const val KEY_ACTIVE_TOPIC = "active_topic"

        @Volatile
        private var instance: PanelLayoutPrefs? = null

        fun getInstance(context: Context): PanelLayoutPrefs =
            instance ?: synchronized(this) {
                instance ?: PanelLayoutPrefs(context).also { instance = it }
            }
    }
}
