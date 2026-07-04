package com.raidcoach.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val securePrefs = SecurePrefs.getInstance(this)

        val apiKeyInput = EditText(this).apply {
            hint = "Anthropic API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(securePrefs.getApiKey().orEmpty())
        }

        val briefingInput = EditText(this).apply {
            hint = "Extra context for the coach (optional)"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(securePrefs.getBriefing().orEmpty())
        }

        val webSearchSwitch = Switch(this).apply {
            text = "Allow web search"
            isChecked = securePrefs.getWebSearchEnabled()
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                securePrefs.setApiKey(apiKeyInput.text.toString().trim())
                securePrefs.setBriefing(briefingInput.text.toString().trim())
                securePrefs.setWebSearchEnabled(webSearchSwitch.isChecked)
                finish()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))

            addView(TextView(context).apply { text = "API key" })
            addView(apiKeyInput)
            addView(
                TextView(context).apply {
                    text = "Coach briefing"
                    setPadding(0, dp(16), 0, 0)
                }
            )
            addView(briefingInput)
            addView(webSearchSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
            addView(
                saveButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            )
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
