package com.d4viddf.hyperbridge.util

import android.content.Context
import android.app.Notification
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.integration.shizuku.XmsfNetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ShizukuManager {
    
    private val _isShizukuRunning = MutableStateFlow(false)
    val isShizukuRunning: StateFlow<Boolean> = _isShizukuRunning.asStateFlow()

    private val _isPermissionGranted = MutableStateFlow(false)
    val isPermissionGranted: StateFlow<Boolean> = _isPermissionGranted.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _isShizukuRunning.value = true
        updatePermissionState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _isShizukuRunning.value = false
        _isPermissionGranted.value = false
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _isPermissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        
        // Initial state check
        try {
            if (Shizuku.pingBinder()) {
                _isShizukuRunning.value = true
                updatePermissionState()
            }
        } catch (_: Exception) {
            _isShizukuRunning.value = false
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        if (rikka.sui.Sui.isSui()) return true
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun updatePermissionState() {
        _isPermissionGranted.value = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission(context: Context, requestCode: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                com.d4viddf.hyperbridge.integration.shizuku.requireShizukuPermissionGranted {
                    _isPermissionGranted.value = true
                    android.widget.Toast.makeText(context, "Shizuku Permission Granted!", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error: " + e.message, android.widget.Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }

    private const val TAG = "HyperBridgeShizuku"
    private var restoreNetworkJob: Job? = null
    private val notifyMutex = Mutex()
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun dispatchWithOptionalWorkaround(
        context: Context,
        operation: suspend () -> Unit
    ) {
        val enabled = _isPermissionGranted.value &&
            AppPreferences.getInstance(context).isShizukuWorkaroundEnabled.first()

        notifyMutex.withLock {
            if (!enabled) {
                operation()
                return@withLock
            }

            // All dispatches share the mutex; restoration is rescheduled after
            // each burst, and even failed dispatches must restore XMSF.
            restoreNetworkJob?.cancel()
            restoreNetworkJob = null
            try {
                if (XmsfNetworkHelper.setXmsfNetworkingEnabled(context, false)) {
                    delay(50)
                }
                operation()
            } finally {
                restoreNetworkJob = notificationScope.launch {
                    delay(1000)
                    notifyMutex.withLock {
                        if (!XmsfNetworkHelper.setXmsfNetworkingEnabled(context, true)) {
                            android.util.Log.e(TAG, "Failed to restore XMSF networking")
                        }
                    }
                }
            }
        }
    }

    private fun dispatchAsync(context: Context, operation: suspend () -> Unit) {
        notificationScope.launch {
            try {
                dispatchWithOptionalWorkaround(context, operation)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Notification operation failed", error)
            }
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    fun notify(context: Context, id: Int, notification: Notification) {
        dispatchAsync(context) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    /** Ordered, same-ID dispatch for stateful island updates. */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    suspend fun notifyInPlace(context: Context, id: Int, notification: Notification) {
        dispatchWithOptionalWorkaround(context) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    fun cancel(context: Context, id: Int) {
        dispatchAsync(context) {
            NotificationManagerCompat.from(context).cancel(id)
        }
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    fun notifyWithCancel(context: Context, id: Int, notification: Notification) {
        dispatchAsync(context) {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(id)
            delay(20)
            manager.notify(id, notification)
        }
    }
}
