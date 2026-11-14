// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

@Singleton
class ShizukuConnectionManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(ShizukuConnectionState())
    val state = _state.asStateFlow()
    private val bindLock = Any()
    private val generation = AtomicLong()
    @Volatile private var service: IShizukuShellService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellService::class.java))
            .tag("droidance-shell-bootstrap").processNameSuffix("shizuku_bootstrap")
            .version(context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.hashCode())
            .daemon(false)
    }

    init {
        if (!RuntimeProcessRole.isOwner) {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                generation.incrementAndGet()
                service = null
                connection = null
                refresh()
            }
        }
    }

    fun refresh(): ShizukuConnectionState {
        if (RuntimeProcessRole.isOwner) return _state.value
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE_NAME, 0) }.isSuccess
        val next = runCatching {
            if (!Shizuku.pingBinder()) ShizukuConnectionState(installed = installed)
            else {
                val supported = !Shizuku.isPreV11() && Shizuku.getVersion() >= 12
                ShizukuConnectionState(installed = installed, running = true,
                    supportedVersion = supported, shellIdentity = Shizuku.getUid() == 2000,
                    authorized = supported && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
                    permissionBlocked = supported && Shizuku.shouldShowRequestPermissionRationale())
            }
        }.getOrElse { ShizukuConnectionState(installed = installed) }
        _state.value = next
        return next
    }

    /** Called only by an explicit foreground connection action. */
    suspend fun requestAuthorization(): Boolean {
        val current = refresh()
        if (current.ready) return true
        if (!current.running || !current.supportedVersion || !current.shellIdentity || current.permissionBlocked) return false
        return suspendCancellableCoroutine { continuation ->
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                if (code == REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    refresh()
                    if (continuation.isActive) continuation.resume(result == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try { Shizuku.requestPermission(REQUEST_CODE) }
            catch (_: Exception) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    /** Never replay after a Binder exception: the launch may already have been delivered. */
    fun shell(command: String, timeoutMs: Long): String {
        if (!refresh().ready) return "[SHIZUKU_UNAVAILABLE]"
        return try {
            val remote = bindShell() ?: return "[SHIZUKU_SERVICE_UNAVAILABLE]"
            val result = remote.execute(command, timeoutMs.coerceIn(1, 120_000), UUID.randomUUID().toString())
            when {
                result.getBoolean("truncated") -> "[SHIZUKU_OUTPUT_TRUNCATED]"
                result.getString("execution_status") != "SUCCEEDED" ->
                    "[SHIZUKU_EXECUTION_FAILED] ${result.getString("diagnostic_code").orEmpty()}"
                else -> result.getString("stdout").orEmpty() + result.getString("stderr").orEmpty()
            }
        } catch (_: InterruptedException) {
            releaseUserService()
            Thread.currentThread().interrupt()
            "[SHIZUKU_INTERRUPTED]"
        } catch (_: Exception) {
            releaseUserService()
            "[SHIZUKU_DELIVERY_UNKNOWN]"
        }
    }

    private fun bindShell(): IShizukuShellService? = synchronized(bindLock) {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@synchronized it }
        val attempt = generation.incrementAndGet()
        val latch = CountDownLatch(1)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (generation.get() == attempt && binder != null) service = IShizukuShellService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (generation.get() == attempt) service = null
                latch.countDown()
            }
        }
        connection = callback
        Shizuku.bindUserService(serviceArgs, callback)
        if (!latch.await(10, TimeUnit.SECONDS)) { releaseUserService(); return@synchronized null }
        service?.takeIf { it.asBinder().isBinderAlive && it.uid == 2000 }
            .also { if (it == null) releaseUserService() }
    }

    fun releaseUserService() {
        generation.incrementAndGet()
        service = null
        val callback = connection
        connection = null
        if (callback != null) {
            // Detach the SDK callback before destroying the process. Otherwise its late death
            // notification can clear a new binding made immediately after this release.
            runCatching { Shizuku.unbindUserService(serviceArgs, callback, false) }
            runCatching { Shizuku.unbindUserService(serviceArgs, null, true) }
        }
    }

    fun openManager(): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun openDownload(): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    companion object {
        const val PACKAGE_NAME = "moe.shizuku.privileged.api"
        const val PERMISSION_NAME = "moe.shizuku.manager.permission.API_V23"
        private const val REQUEST_CODE = 4096
    }
}
