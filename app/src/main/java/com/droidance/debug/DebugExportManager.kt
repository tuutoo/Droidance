// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.content.Context
import android.os.Build
import com.droidance.BuildConfig
import com.droidance.core.device.air.AirModePairingManager
import com.droidance.core.agent.blob.BlobStore
import com.droidance.memory.MarkdownFileManager
import com.droidance.core.device.tools.AdbShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One-click export of all retained history, with per-task indexes and verified screenshots. */
@Singleton
class DebugExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: DiagnosticsCollector,
    private val adbShell: AdbShellExecutor,
    private val sidecarBridge: com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge,
    private val airModePairingManager: AirModePairingManager,
    private val agentEventReplayExporter: AgentEventReplayExporter,
    private val blobStore: BlobStore,
    private val fileManager: MarkdownFileManager
) {
    suspend fun exportZip(): File {
        val shareDir = File(context.cacheDir, "share")
        if (!shareDir.isDirectory && !shareDir.mkdirs()) throw IOException("Debug export directory is unavailable")
        com.droidance.agent.cleanExportCache(context.cacheDir, System.currentTimeMillis(), com.droidance.agent.RuntimeCachePolicy())
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val stagingFile = File.createTempFile("Droidance-debug-$timestamp-", ".zip.partial", shareDir)
        val zipFile = File(shareDir, stagingFile.name.removeSuffix(".partial"))
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = agentEventReplayExporter.exportSnapshot()
            val airShellHealth = airModePairingManager.shellHealthSnapshot()
            diagnostics.writeEnvSnapshot(
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT.toString(),
                adbShellAvailable = adbShell.isAvailable(),
                imeId = null,
                buildType = if (context.packageName.contains("debug")) "debug" else "release",
                airPreviouslyPaired = airShellHealth.previouslyPaired,
                airPairingState = airShellHealth.pairingState.name.lowercase(Locale.US),
                airPairingTrustState = airShellHealth.pairingTrustState.name.lowercase(Locale.US),
                airWifiTransportAvailable = airShellHealth.wifiTransportAvailable,
                airShellReady = airShellHealth.ready,
                airShellState = airShellHealth.state.name.lowercase(Locale.US),
                airShellLastFailure = airShellHealth.lastFailure,
                airShellLastCheckedAtMs = airShellHealth.lastCheckedAtMs,
                airActiveEndpoint = airShellHealth.activeEndpoint?.endpointId.orEmpty(),
                airEndpointSource = airShellHealth.activeEndpoint?.source.orEmpty(),
                airShellLastProbeOutput = airShellHealth.probeOutput,
                airAdbPersistenceEnabled = airShellHealth.persistenceEnabled,
                airLocalAdbPort = airShellHealth.localPort,
                airUsbDebuggingEnabled = airShellHealth.usbDebuggingEnabled,
                adbdEvidence = airShellHealth.adbdEvidence,
                airPairingFailure = airShellHealth.pairingFailure?.name.orEmpty(),
                airNotificationAvailability = airShellHealth.notificationAvailability.name,
                airSecureSettingsGranted = airShellHealth.secureSettingsGranted,
                airWirelessDebuggingEnabled = airShellHealth.wirelessDebuggingEnabled
            )

            diagnostics.writeSidecarEvidence(sidecarBridge.readSidecarEvidence())

            val root = File(fileManager.getDataDirPath())
            DebugExportSources.withSources(root, context.cacheDir, snapshot, blobStore) { supplements ->
                ZipOutputStream(stagingFile.outputStream().buffered()).use { zip ->
                    DebugArchiveWriter(blobStore).write(zip, snapshot, buildJsonObject {
                        put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                        put("package", context.packageName)
                        put("version", BuildConfig.VERSION_NAME)
                        put("build", if (BuildConfig.DEBUG) "debug" else "release")
                    }, supplements)
                }
            }
            currentCoroutineContext().ensureActive()
            if (zipFile.exists() || !stagingFile.renameTo(zipFile)) {
                throw IOException("Unable to publish completed debug export")
            }
            return zipFile
        } finally {
            stagingFile.delete()
        }
    }

}
