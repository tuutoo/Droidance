// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.debug

import android.content.Context
import android.os.Build
import com.droidance.BuildConfig
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.device.air.AirModePairingManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the redacted diagnostics package that users can share with developers. */
@Singleton
class DiagnosticsReportExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: AgentEventStore,
    private val pairing: AirModePairingManager,
    private val sidecarBridge: com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
) {
    suspend fun export(): File {
        val shareDir = File(context.cacheDir, "share")
        if (!shareDir.isDirectory && !shareDir.mkdirs()) throw IOException("Diagnostics directory is unavailable")
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(shareDir, "Droidance-diagnostics-$timestamp.zip")
        val steps = RedactedDiagnostics.events(events.exportSnapshot())
        val sidecar = RedactedDiagnostics.maskLog(runCatching { sidecarBridge.readSidecarEvidence() }.getOrDefault(""))
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("diagnostics.json", RedactedDiagnostics.line(summary(steps.size)))
            entry("task-steps.jsonl", steps.joinToString("\n", transform = RedactedDiagnostics::line))
            entry("service.log", sidecar)
        }
        return file
    }

    private fun summary(stepCount: Int): JsonObject {
        val health = pairing.shellHealthSnapshot()
        return buildJsonObject {
            put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            put("app", buildJsonObject {
                put("version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("build", if (BuildConfig.DEBUG) "debug" else "release")
            })
            put("device", buildJsonObject {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("rom", Build.DISPLAY)
            })
            put("connection", buildJsonObject {
                put("state", health.state.name)
                put("ready", health.ready)
                put("pairing_state", health.pairingState.name)
                put("pairing_trust", health.pairingTrustState.name)
                put("previously_paired", health.previouslyPaired)
                health.wifiTransportAvailable?.let { put("wifi_transport", it) }
                health.wirelessDebuggingEnabled?.let { put("wireless_debugging", it) }
                health.usbDebuggingEnabled?.let { put("usb_debugging", it) }
                put("secure_settings_granted", health.secureSettingsGranted)
                put("adb_persistence", health.persistenceEnabled)
                health.pairingFailure?.let { put("pairing_failure", it.name) }
            })
            put("task_steps", stepCount)
        }
    }
}
