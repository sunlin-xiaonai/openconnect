package com.agmente.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agmente.android.ui.theme.AgmenteAndroidTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "agmente_permissions"
        private const val KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"
    }

    private val viewModel: AcpViewModel by viewModels()
    private var notificationsGranted by mutableStateOf(false)

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchPairScanner()
        } else {
            val message = getString(R.string.toast_camera_permission_denied)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.onScannerFailure(message)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                getString(R.string.toast_notifications_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val pairScanner = registerForActivityResult(ScanContract()) { result ->
        val rawValue = result.contents?.trim()
        if (rawValue.isNullOrBlank()) {
            viewModel.onScannerCancelled()
            return@registerForActivityResult
        }
        viewModel.consumeScannedCode(rawValue)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgmenteNotifications.initialize(this)
        viewModel.consumeIntent(intent)
        notificationsGranted = hasNotificationPermission()
        maybeRequestNotificationPermission()

        setContent {
            AgmenteAndroidTheme {
                AgmenteApp(
                    viewModel = viewModel,
                    onScanPairCode = ::startPairScanner,
                    notificationsGranted = notificationsGranted,
                    onRequestNotificationPermission = ::requestNotificationPermissionNow,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.consumeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AgmenteNotifications.setAppVisible(true)
    }

    override fun onStop() {
        AgmenteNotifications.setAppVisible(false)
        super.onStop()
    }

    private fun startPairScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchPairScanner()
            return
        }

        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchPairScanner() {
        val options = ScanOptions().apply {
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_prompt_pair_qr))
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
        }
        pairScanner.launch(options)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (hasNotificationPermission()) {
            return
        }
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)) {
            return
        }
        preferences.edit()
            .putBoolean(KEY_NOTIFICATIONS_REQUESTED, true)
            .apply()
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestNotificationPermissionNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = true
            return
        }
        if (hasNotificationPermission()) {
            notificationsGranted = true
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
