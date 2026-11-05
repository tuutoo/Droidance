// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

/** Bind the same application graph to shell-owned storage and shell service attribution. */
internal object RuntimeApplicationContext {
    // Runs only in the shell app_process, which is not subject to application hidden-API enforcement.
    @android.annotation.SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
    fun create(shell: Context, packageName: String, root: File): Application {
        check(com.droidance.core.device.agent.device.RuntimeProcessRole.isOwner)
        val info = ApplicationInfo(shell.packageManager.getApplicationInfo(packageName, 0)).apply {
            uid = android.os.Process.myUid()
            dataDir = root.path
            deviceProtectedDataDir = root.path
        }
        ApplicationInfo::class.java.getDeclaredField("credentialProtectedDataDir")
            .apply { isAccessible = true }.set(info, root.path)
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val compatibilityClass = Class.forName("android.content.res.CompatibilityInfo")
        val compatibility = compatibilityClass.getField("DEFAULT_COMPATIBILITY_INFO").get(null)
        val loaded = threadClass.getMethod("getPackageInfoNoCheck", ApplicationInfo::class.java,
            compatibilityClass).invoke(thread, info, compatibility)
        val bindingClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val binding = bindingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        for ((name, value) in listOf("appInfo" to info, "info" to loaded, "processName" to "$packageName:runtime")) {
            bindingClass.getDeclaredField(name).apply { isAccessible = true }.set(binding, value)
        }
        threadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, binding)
        val contextClass = Class.forName("android.app.ContextImpl")
        val context = contextClass.getDeclaredMethod("createAppContext", threadClass,
            Class.forName("android.app.LoadedApk")).apply { isAccessible = true }
            .invoke(null, thread, loaded) as Context
        contextClass.getDeclaredField("mOpPackageName").apply { isAccessible = true }.set(context, "com.android.shell")
        if (Build.VERSION.SDK_INT >= 31) {
            contextClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }.set(context,
                android.content.AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell").build())
        }
        android.content.ContentResolver::class.java.getDeclaredField("mPackageName")
            .apply { isAccessible = true }.set(context.contentResolver, "com.android.shell")
        com.droidance.core.device.agent.device.RuntimeContentProviders.acquire("settings")
        // HyperOS 4 on Xiaomi Android 17 recursively re-enters Typeface.create
        // when app_process explicitly installs the system font map. Keep the
        // standard bootstrap on other devices without changing system settings.
        val skipVendorFontMap = Build.VERSION.SDK_INT >= 37 &&
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        if (skipVendorFontMap) {
            android.util.Log.w("DroidanceRuntime", "Skipping font-map bootstrap on Xiaomi Android 17")
        }
        // app_process does not receive ActivityThread.bindApplication's font initialization.
        if (Build.VERSION.SDK_INT >= 31 && !skipVendorFontMap) {
            val manager = requireNotNull(shell.getSystemService("font"))
            val config = manager.javaClass.getMethod("getFontConfig").invoke(manager)
            val systemFonts = Class.forName("android.graphics.fonts.SystemFonts")
            val configClass = Class.forName("android.text.FontConfig")
            val fallback = systemFonts.getMethod("buildSystemFallback", configClass).invoke(null, config)
            val map = systemFonts.getMethod("buildSystemTypefaces", configClass, Map::class.java)
                .invoke(null, config, fallback)
            android.graphics.Typeface::class.java.getMethod("setSystemFontMap", Map::class.java).invoke(null, map)
        }
        // Obtain WMS directly: raw shell processes do not receive zygote application shared memory.
        val windowBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "window")
        val windows = Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, windowBinder)
        Class.forName("android.view.WindowManagerGlobal").getDeclaredField("sWindowManagerService")
            .apply { isAccessible = true }.set(null, windows)
        threadClass.getDeclaredField("mInstrumentation").apply { isAccessible = true }
            .set(thread, android.app.Instrumentation())
        // ActivityThread.systemMain may already install AndroidNSSP in this
        // standalone shell process. Reinstalling the same provider returns -1.
        val existingSecurityProvider = java.security.Security.getProviders().firstOrNull()?.name
        if (skipVendorFontMap && existingSecurityProvider == "AndroidNSSP") {
            android.util.Log.i("DroidanceRuntime", "Using existing Android network security provider")
        } else {
            Class.forName("android.security.net.config.NetworkSecurityConfigProvider")
                .getMethod("install", Context::class.java).invoke(null, context)
        }
        val application = com.droidance.DroidanceApp()
        Application::class.java.getDeclaredMethod("attach", Context::class.java)
            .apply { isAccessible = true }.invoke(application, context)
        Class.forName("android.app.LoadedApk").getDeclaredField("mApplication")
            .apply { isAccessible = true }.set(loaded, application)
        threadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(thread, application)
        return application
    }
}
