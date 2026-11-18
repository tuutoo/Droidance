// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.agent.device

import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.droidance.core.device.agent.device.INativeInputDriver
import com.droidance.core.device.agent.device.IVirtualSpaceSidecar
import com.droidance.core.device.agent.device.RuntimePlatformDriver
import com.droidance.core.device.agent.device.SidecarBinderHandoff
import com.droidance.core.device.agent.device.SidecarBinderReceiver
import com.droidance.core.device.agent.device.VirtualSpaceSidecarService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** System input driver only. No model, task queue or application graph runs in this process. */
class RuntimeDriverBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val binder = SidecarBinderHandoff.receive(context, intent) ?: return
        if (registration?.sidecar == binder) {
            if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
            return
        }
        val sidecar = IVirtualSpaceSidecar.Stub.asInterface(binder)
        val driver = NativePlatformDriver(context.applicationContext)
        val current = Registration(binder)
        val death = IBinder.DeathRecipient {
            Handler(Looper.getMainLooper()).post {
                if (registration === current) registration = null
                driver.close()
            }
        }
        try {
            binder.linkToDeath(death, 0)
            sidecar.setPlatformDriver(driver.binder)
            registration = current
            if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
        } catch (_: android.os.RemoteException) {
            runCatching { binder.unlinkToDeath(death, 0) }
            driver.close()
            Log.w(TAG, "Sidecar disconnected before the input driver was registered")
        } catch (error: RuntimeException) {
            runCatching { binder.unlinkToDeath(death, 0) }
            driver.close()
            Log.w(TAG, "Input driver registration failed: ${error.javaClass.simpleName}")
        }
    }

    private class Registration(val sidecar: IBinder)

    companion object {
        private const val TAG = "DroidanceVSpace"
        // Read and written only by the driver process's main looper.
        private var registration: Registration? = null

        internal fun requestFromApp(context: Context, sidecar: IBinder): Boolean {
            if (!sidecar.isBinderAlive) return false
            val intent = Intent(context, RuntimeDriverBinderReceiver::class.java)
                .setAction(SidecarBinderReceiver.ACTION_SIDECAR_BINDER_READY)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            SidecarBinderHandoff.put(intent, sidecar, 2000, VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION,
                context.applicationInfo.sourceDir, context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime)
            val completed = CountDownLatch(1)
            var registered = false
            val result = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    registered = resultCode == Activity.RESULT_OK
                    completed.countDown()
                }
            }
            val callbacks = HandlerThread("platform-driver-recovery").apply { start() }
            return try {
                Log.i(TAG, "Retrying platform driver handoff from the foreground app")
                context.sendOrderedBroadcast(intent, null, result, Handler(callbacks.looper), Activity.RESULT_CANCELED, null, null)
                completed.await(RuntimePlatformDriver.BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) && registered
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (error: RuntimeException) {
                Log.w(TAG, "App platform driver handoff failed: ${error.javaClass.simpleName}")
                false
            } finally {
                callbacks.quitSafely()
            }
        }
    }
}

private class NativePlatformDriver(private val context: Context) {
    private val programs = java.util.concurrent.ConcurrentHashMap<String, android.content.ServiceConnection>()
    fun close() {
        programs.values.forEach { runCatching { context.unbindService(it) } }
        programs.clear()
        Handler(Looper.getMainLooper()).post { AgentTextInputMethodService.endSession(context, false) }
    }
    val binder = object : INativeInputDriver.Stub() {
        override fun getWindowSession(): android.os.IBinder {
            check(Binder.getCallingUid() == 2000)
            val identity = Binder.clearCallingIdentity()
            return try {
                val window = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowSession").invoke(null)
                Class.forName("android.view.IWindowSession").getMethod("asBinder").invoke(window) as android.os.IBinder
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        override fun startProgram(instance: String): android.os.IBinder {
            check(Binder.getCallingUid() == 2000)
            if (android.os.Build.VERSION.SDK_INT < 29) throw IllegalStateException("PROGRAM_REQUIRES_API_29")
            val connected = java.util.concurrent.CompletableFuture<android.os.IBinder>()
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) { connected.complete(binder) }
                override fun onServiceDisconnected(name: android.content.ComponentName) { programs.remove(instance, this) }
                override fun onNullBinding(name: android.content.ComponentName) { connected.completeExceptionally(IllegalStateException("Program worker unavailable")) }
            }
            check(programs.putIfAbsent(instance, connection) == null)
            val identity = Binder.clearCallingIdentity()
            try {
                check(context.bindIsolatedService(Intent(context, com.droidance.core.device.program.ProgramWorkerService::class.java),
                    Context.BIND_AUTO_CREATE, instance, context.mainExecutor, connection))
                return connected.get(5, TimeUnit.SECONDS)
            } catch (error: Exception) { programs.remove(instance); runCatching { context.unbindService(connection) }; throw error }
            finally { Binder.restoreCallingIdentity(identity) }
        }
        override fun closeProgram(instance: String) {
            check(Binder.getCallingUid() == 2000)
            val identity = Binder.clearCallingIdentity()
            try { programs.remove(instance)?.let { context.unbindService(it) } }
            finally { Binder.restoreCallingIdentity(identity) }
        }

        override fun command(operation: String, arguments: Bundle): android.os.ParcelFileDescriptor {
            check(Binder.getCallingUid() == 2000) { "Only the resident shell may control input" }
            if (operation == "content") {
                val identity = Binder.clearCallingIdentity()
                return try { com.droidance.core.device.agent.device.RuntimeWire.write(context.cacheDir, content(arguments)) }
                    finally { Binder.restoreCallingIdentity(identity) }
            }
            val work = FutureTask { dispatch(operation, arguments) }
            Handler(Looper.getMainLooper()).post(work)
            val reply = try { work.get(5, TimeUnit.SECONDS) }
                catch (error: Exception) { work.cancel(false); throw error }
            return com.droidance.core.device.agent.device.RuntimeWire.write(context.cacheDir, reply)
        }
    }

    private fun content(args: Bundle): Bundle = kotlinx.coroutines.runBlocking {
        val broker = com.droidance.core.device.permission.PermissionBroker(context) {
            error("Permissions must be granted before dispatch to the platform driver")
        }
        val service = com.droidance.core.device.tools.AndroidContentQueryService(context,
            broker, { "platform" to "driver" }, { "Platform content operation" }, { 0L }, { }, { _, _ -> }, null)
        val input = args.getString("arguments")!!
        val result = when (args.getString("operation")) {
            "query" -> service.query(input)
            "insert" -> service.insert(input)
            "update" -> service.update(input)
            "delete" -> service.delete(input)
            "batch" -> service.applyBatch(input)
            else -> error("Unknown content operation")
        }
        Bundle().apply { putString("result", kotlinx.serialization.json.Json.encodeToString(
            com.droidance.core.agent.event.ToolExecutionResult.serializer(), result)) }
    }

    /** Running-task status is quiet; results alert, and each notice alerts at most once. */
    private fun notifyFromRuntime(args: Bundle) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val ongoing = args.getBoolean("ongoing")
        val channel = if (ongoing) android.app.NotificationChannel(RUNTIME_STATUS_CHANNEL,
            args.getString("statusChannel"), android.app.NotificationManager.IMPORTANCE_LOW)
        else android.app.NotificationChannel(RUNTIME_RESULT_CHANNEL, args.getString("resultChannel"),
            android.app.NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
        val id = args.getInt("id")
        val text = args.getString("text").orEmpty()
        val launch = Intent().setClassName(context.packageName, "com.droidance.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = android.app.PendingIntent.getActivity(context, id, launch,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        manager.notify(RUNTIME_NOTIFICATION_TAG, id, android.app.Notification.Builder(context, channel.id)
            .setSmallIcon(com.droidance.R.drawable.ic_launcher_monochrome)
            .setContentTitle(args.getString("title")).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(content).setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true).build())
    }

    private fun dispatch(operation: String, args: Bundle) = Bundle().apply {
        when (operation) {
            "probeReset" -> { VirtualSpaceProbeLanding.displayId = null }
            "probeFinish" -> VirtualSpaceProbeLanding.finishActive()
            "probeState" -> {
                VirtualSpaceProbeLanding.displayId?.let { putInt("display", it) }
                putLong("at", VirtualSpaceProbeLanding.landedAtMs)
            }
            "notifications" -> {
                val watcher = com.droidance.automation.NotificationWatcher.instance
                putBoolean("available", watcher != null)
                putString("items", kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.droidance.automation.NotificationInfo.serializer()),
                    watcher?.getAllNotifications().orEmpty()))
            }
            "notificationClick" -> putBoolean("result", com.droidance.automation.NotificationWatcher.instance
                ?.clickNotification(args.getString("key")!!, if (args.containsKey("display")) args.getInt("display") else null) == true)
            "notificationDismiss" -> com.droidance.automation.NotificationWatcher.instance?.dismissNotification(args.getString("key")!!)
            // Framework JNI libraries the zygote preloaded into this application process.
            "systemJniLibraries" -> putStringArrayList("paths", ArrayList(
                java.io.File("/proc/self/maps").readLines().mapNotNull { it.substringAfter('/', "").takeIf(String::isNotEmpty) }
                    .map { "/$it" }.distinct().filter { path ->
                        !path.startsWith("/data/") && !path.startsWith("/apex/") && path.endsWith(".so") &&
                            path.substringAfterLast('/').contains("jni", ignoreCase = true)
                    }))
            "notify" -> notifyFromRuntime(args)
            "cancelNotification" -> context.getSystemService(android.app.NotificationManager::class.java)
                .cancel(RUNTIME_NOTIFICATION_TAG, args.getInt("id"))
            "restore" -> putString("ime", AgentTextInputMethodService.restorationTarget(context))
            "begin" -> AgentTextInputMethodService.beginSession(context, args.getString("ime")!!)
            "end" -> AgentTextInputMethodService.endSession(context, args.getBoolean("restored"))
            "delivered" -> putBoolean("delivered", AgentTextInputMethodService.pendingCommitDelivered())
            "commit" -> {
                val display = args.getInt("display")
                val pkg = args.getString("package")!!
                putString("state", AgentTextInputMethodService.connectionState(display, pkg))
                AgentTextInputMethodService.commit(display, pkg, args.getString("text")!!, args.getBoolean("clear"))?.let {
                    putBoolean("dispatched", true); putBoolean("success", it.success); putString("message", it.message)
                    putString("diagnostic", it.diagnosticCode)
                    it.textInputEvidence?.let { evidence ->
                        putBoolean("focus", evidence.focusConfirmed); putBoolean("payload", evidence.payloadConfirmed)
                        putBoolean("injected", evidence.eventInjected); putBoolean("changed", evidence.screenChanged)
                        putBoolean("verified", evidence.deliveryVerified)
                    }
                }
            }
            else -> error("Unknown input driver operation")
        }
    }
}

private const val RUNTIME_NOTIFICATION_TAG = "Droidance"
private const val RUNTIME_STATUS_CHANNEL = "droidance_resident_status"
private const val RUNTIME_RESULT_CHANNEL = "droidance_resident_tasks"
