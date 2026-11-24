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
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * One-time setup screen: device name plus the permissions the listener needs, then it starts the
 * foreground service and hides its own launcher entry. After that the app has no UI at all and
 * runs in the background on the fixed ntfy topic.
 *
 * Nothing closes by itself: after the name is saved the screen stays open showing the permission
 * buttons, and only the user's "Done" tap hides the launcher icon and finishes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nameInput: EditText
    private lateinit var stepName: View
    private lateinit var stepPerms: View
    private lateinit var status: TextView

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { showPermissionsStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nameInput = findViewById(R.id.deviceName)
        stepName = findViewById(R.id.stepName)
        stepPerms = findViewById(R.id.stepPerms)
        status = findViewById(R.id.status)
        nameInput.setText(prefs().getString(NtfyAlarmService.KEY_NAME, ""))

        findViewById<Button>(R.id.btnEnable).setOnClickListener { saveAndContinue() }
        findViewById<Button>(R.id.btnBackground).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener { openAppSettings() }
        findViewById<Button>(R.id.btnDnd).setOnClickListener { requestDndAccess() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finishSetup() }

        // Name already saved earlier (the app was closed before finishing): resume at the
        // permission step instead of pretending setup is complete.
        if (prefs().getBoolean(NtfyAlarmService.KEY_SETUP_DONE, false)) {
            startListener()
            showPermissionsStep()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
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
            showPermissionsStep()
        }
    }

    /** Shows the permission buttons. The screen stays open until the user taps Done. */
    private fun showPermissionsStep() {
        startListener()
        stepName.visibility = View.GONE
        stepPerms.visibility = View.VISIBLE
        updateStatus()
    }

    private fun updateStatus() {
        if (stepPerms.visibility != View.VISIBLE) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val bg = if (pm.isIgnoringBatteryOptimizations(packageName)) "allowed" else "NOT allowed"
        val dnd = if (nm.isNotificationPolicyAccessGranted) "allowed" else "NOT allowed"
        status.text = getString(R.string.setup_status, bg, dnd)
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

    /**
     * Asks the system to stop battery-optimising the app. This is the "always run in background"
     * switch; on phones that hide it, the app-settings button below opens the same page.
     */
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Background running is already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            openAppSettings()
        }
    }

    /** App info page: battery usage, auto-start and "allow background activity" live there. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
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
        if (nm.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, "Sound changes are already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
        }
    }

    /** The user's own "I am finished" tap: hide the launcher entry and close the window. */
    private fun finishSetup() {
        startListener()
        hideLauncherIcon()
        Toast.makeText(this, "Bytello keeps running in the background", Toast.LENGTH_SHORT).show()
        finish()
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
