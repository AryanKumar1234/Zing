package com.example.ntfyalarm

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * One-time setup screen: device name plus the permissions the listener needs, then it starts the
 * foreground service and hides its own launcher entry. After that the app has no UI at all and
 * runs in the background on the fixed ntfy topic.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private val handler = Handler(Looper.getMainLooper())

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { finishSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Already set up: start the listener and close instantly, showing nothing.
        if (prefs().getBoolean(NtfyAlarmService.KEY_SETUP_DONE, false)) {
            startListener()
            hideLauncherIcon()
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        nameInput = findViewById(R.id.deviceName)
        nameInput.setText(prefs().getString(NtfyAlarmService.KEY_NAME, ""))

        findViewById<Button>(R.id.btnEnable).setOnClickListener { saveAndContinue() }
    }

    private fun prefs() = getSharedPreferences(NtfyAlarmService.PREFS, Context.MODE_PRIVATE)

    private fun saveAndContinue() {
        val name = nameInput.text.toString().trim()
        if (!Regex("^[A-Za-z0-9_.-]{1,32}$").matches(name)) {
            Toast.makeText(this, "Device name: 1-32 letters, digits, _ . - (no spaces)", Toast.LENGTH_LONG).show()
            return
        }
        prefs().edit()
            .putString(NtfyAlarmService.KEY_NAME, name)
            .putBoolean(NtfyAlarmService.KEY_SETUP_DONE, true)
            .apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finishSetup()
        }
    }

    /** Everything the app needs to run unattended; then the launcher entry goes away. */
    private fun finishSetup() {
        startListener()
        requestBatteryExemption()
        handler.postDelayed({ requestDndAccess() }, 800)
        handler.postDelayed({
            hideLauncherIcon()
            finish()
        }, 1600)
    }

    private fun startListener() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NtfyAlarmService::class.java).setAction(NtfyAlarmService.ACTION_START)
            )
        } catch (_: Exception) {
        }
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun requestDndAccess() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) return
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    /** Removes the launcher entry; the foreground service keeps running. */
    private fun hideLauncherIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.Setup"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
        }
    }
}
