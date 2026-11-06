// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.backup

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The application's own settings that belong to the user: preferences, composer and skill
 * authoring choices, and model providers with their credentials. Pairing, connection and
 * update bookkeeping describe this device and stay with it.
 */
object AppSettingsBackup {
    val FILES = listOf(
        "droidance_settings", "droidance_composer", "droidance_providers",
        "droidance_reasoning_capabilities", "skill-authoring"
    )
    private val DEVICE_KEYS = Regex(
        "air_.*|droidance_air_ready|device_connection_method|virtual_display_id|" +
            "last_update_check_time|last_prompted_update_version|known_update_version"
    )

    fun isDeviceKey(key: String): Boolean = DEVICE_KEYS.matches(key)

    /** Typed values of every backed-up preference file. */
    fun encode(files: Map<String, Map<String, *>>): JsonObject = JsonObject(files.mapValues { (_, values) ->
        JsonObject(values.filterKeys { !isDeviceKey(it) }.mapNotNull { (key, value) ->
            val typed = when (value) {
                is String -> "s" to JsonPrimitive(value)
                is Boolean -> "b" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is Set<*> -> "ss" to JsonArray(value.map { JsonPrimitive(it as String) })
                else -> return@mapNotNull null
            }
            key to JsonObject(mapOf("t" to JsonPrimitive(typed.first), "v" to typed.second))
        }.toMap())
    })

    fun decode(json: JsonObject): Map<String, Map<String, Any>> = json.mapValues { (file, values) ->
        require(file in FILES) { "BACKUP_CORRUPTED" }
        values.jsonObject.mapValues { (_, typed) -> value(typed) }
    }

    fun count(json: JsonObject): Int = json.values.sumOf { it.jsonObject.size }

    fun capture(context: Context): JsonObject =
        encode(FILES.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all })

    /** Replaces the user's settings and keeps this device's own keys. */
    fun apply(context: Context, json: JsonObject) {
        val files = decode(json)
        for (name in FILES) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            preferences.all.keys.filterNot(::isDeviceKey).forEach(editor::remove)
            files[name].orEmpty().filterKeys { !isDeviceKey(it) }.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
            check(editor.commit()) { "Cannot restore settings" }
        }
    }

    private fun value(typed: JsonElement): Any {
        val entry = typed.jsonObject
        val value = entry.getValue("v")
        return when (entry.getValue("t").jsonPrimitive.content) {
            "s" -> value.jsonPrimitive.content
            "b" -> value.jsonPrimitive.boolean
            "i" -> value.jsonPrimitive.int
            "l" -> value.jsonPrimitive.long
            "f" -> value.jsonPrimitive.float
            "ss" -> value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            else -> throw IllegalArgumentException("BACKUP_CORRUPTED")
        }
    }
}
