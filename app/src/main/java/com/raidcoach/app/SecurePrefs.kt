package com.raidcoach.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs private constructor(context: Context) {

    private val preferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        preferences = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String? = preferences.getString(KEY_API_KEY, null)

    fun setApiKey(value: String) {
        preferences.edit().putString(KEY_API_KEY, value).apply()
    }

    fun getBriefing(): String? = preferences.getString(KEY_BRIEFING, null)

    fun setBriefing(value: String) {
        preferences.edit().putString(KEY_BRIEFING, value).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "raid_coach_secure_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BRIEFING = "coach_briefing"

        @Volatile
        private var instance: SecurePrefs? = null

        fun getInstance(context: Context): SecurePrefs =
            instance ?: synchronized(this) {
                instance ?: SecurePrefs(context.applicationContext).also { instance = it }
            }
    }
}
