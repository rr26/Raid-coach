package com.raidcoach.app

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

class RaidCoachApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_STACK_TRACE, Log.getStackTraceString(throwable))
                }
                startActivity(intent)
            } catch (handlerError: Throwable) {
                defaultHandler?.uncaughtException(thread, throwable)
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(1)
            }
        }
    }
}
