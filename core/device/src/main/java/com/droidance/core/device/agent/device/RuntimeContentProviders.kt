// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

import android.os.Binder
import android.os.IBinder

/** Shell processes acquire providers externally because they have no AMS application record. */
object RuntimeContentProviders {
    private val token = Binder()
    @Synchronized
    fun acquire(authority: String) {
        if (!RuntimeProcessRole.isOwner) return
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val providers = threadClass.getDeclaredField("mProviderMap").apply { isAccessible = true }
            .get(thread) as MutableMap<Any, Any>
        val keyClass = Class.forName("android.app.ActivityThread\$ProviderKey")
        val key = keyClass.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(authority, 0)
        synchronized(providers) { if (providers.containsKey(key)) return }
        val recordClass = Class.forName("android.app.ActivityThread\$ProviderClientRecord")
        val recordTypes = arrayOf(Array<String>::class.java, Class.forName("android.content.IContentProvider"),
            Class.forName("android.content.ContentProvider"), Class.forName("android.app.ContentProviderHolder"))
        val constructor = runtimeProviderRecordConstructor(recordClass, threadClass, recordTypes)
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val holder = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, authority, 0, token, "droidance-runtime") ?: error("Content provider unavailable")
        val provider = holder.javaClass.getField("provider").get(holder)
        val arguments = arrayOf(arrayOf(authority), provider, null, holder)
        val record = if (constructor.parameterCount == recordTypes.size) constructor.newInstance(*arguments)
            else constructor.newInstance(thread, *arguments)
        synchronized(providers) { providers[key] = record }
        val binder = Class.forName("android.content.IContentProvider").getMethod("asBinder").invoke(provider) as IBinder
        binder.linkToDeath({ synchronized(providers) { providers.remove(key, record) } }, 0)
    }
}

internal fun runtimeProviderRecordConstructor(
    recordClass: Class<*>,
    threadClass: Class<*>,
    recordTypes: Array<Class<*>>
): java.lang.reflect.Constructor<*> {
    val constructors = recordClass.declaredConstructors
    // Non-static framework records include their enclosing ActivityThread as the first parameter.
    for (types in listOf(recordTypes, arrayOf(threadClass, *recordTypes))) {
        constructors.firstOrNull { it.parameterTypes.contentEquals(types) }?.let {
            return it.apply { isAccessible = true }
        }
    }
    error("Unsupported ${recordClass.name} constructors: ${constructors.joinToString { it.toGenericString() }}")
}
