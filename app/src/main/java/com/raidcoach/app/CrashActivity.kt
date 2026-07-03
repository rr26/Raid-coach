package com.raidcoach.app

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stack trace available"

        val textView = TextView(this).apply {
            text = stackTrace
            textSize = 12f
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        setContentView(scrollView)
    }
}
