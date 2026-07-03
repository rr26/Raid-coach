package com.raidcoach.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CapturePermissionActivity : AppCompatActivity() {

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val serviceIntent = Intent(this, OverlayService::class.java)

        if (result.resultCode == Activity.RESULT_OK && data != null) {
            serviceIntent.action = OverlayService.ACTION_PROJECTION_GRANTED
            serviceIntent.putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
            serviceIntent.putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        } else {
            serviceIntent.action = OverlayService.ACTION_PROJECTION_DENIED
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }
}
