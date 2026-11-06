// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

internal object ResidentSidecarProcessCommands {
    private val packagePattern = Regex("[A-Za-z0-9_.]+")
    private const val MAIN_CLASS = "com.droidance.core.device.agent.device.VirtualSpaceSidecarMain"

    /**
     * The application's own external files directory. The system removes it with the package's
     * data, so resident files never outlive an uninstall or a data reset.
     */
    fun requireStorageDirectory(storageDirectory: String, packageName: String): String {
        require(packagePattern.matches(packageName)) { "invalid sidecar package" }
        val pattern = Regex("/storage/[A-Za-z0-9-]+(/[0-9]+)?/Android/data/${Regex.escape(packageName)}/files")
        require(pattern.matches(storageDirectory)) { "invalid sidecar storage directory" }
        return storageDirectory
    }

    fun logPath(storageDirectory: String): String = "$storageDirectory/sidecar/sidecar.log"

    fun listSupervisorPidsCommand(packageName: String): String {
        require(packagePattern.matches(packageName)) { "invalid sidecar package" }
        return "ps -A -o UID,PID,ARGS | " +
            "awk '(\$1 == \"shell\" || \$1 == \"2000\") && /[D]ROIDANCE_SIDECAR_MAIN/ { print \$2 }' | " +
            "while IFS= read -r pid; do [ -r \"/proc/\$pid/environ\" ] || continue; " +
            "tr '\\000' '\\n' <\"/proc/\$pid/environ\" | grep -Fqx 'DROIDANCE_SIDECAR_PACKAGE=$packageName' " +
            "&& printf '%s\\n' \"\$pid\"; done"
    }

    fun listPidsCommand(
        uid: Int,
        packageName: String,
        protocolVersion: Int? = null
    ): String {
        require(uid == 2000) { "unsupported sidecar uid=$uid" }
        require(packagePattern.matches(packageName)) { "invalid sidecar package" }
        val identity = "(${'$'}1 == \"shell\" || ${'$'}1 == \"2000\")"
        if (protocolVersion != null) {
            require(protocolVersion > 0) { "invalid sidecar protocol=$protocolVersion" }
        }
        val protocol = protocolVersion
            ?.let { " && ${'$'}10 == \"droidance-sidecar-protocol=$it\"" }
            .orEmpty()
        return "ps -A -o UID,PID,ARGS | " +
            "awk -v target='$packageName' '$identity && ${'$'}5 == \"$MAIN_CLASS\" && ${'$'}9 == target$protocol { print ${'$'}2 }'"
    }

    fun parsePids(output: String): List<Int> =
        output.lineSequence()
            .mapNotNull { line -> line.trim().toIntOrNull() }
            .filter { pid -> pid > 1 }
            .distinct()
            .toList()

    /**
     * The supervisor restarts a sidecar that was killed or crashed, without needing ADB. Exit code 0
     * means an intentional stop or a duplicate. Sidecar arguments stay in the environment so the
     * process probes, which match on them, never mistake the supervisor for a sidecar.
     */
    fun launchCommand(
        apkPath: String,
        mainClass: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        packageName: String,
        protocolVersion: Int,
        storageDirectory: String,
        stopWhenDeveloperOptionsDisabled: Boolean = true,
        escape: (String) -> String
    ): String {
        val supervisor = "while :; do " +
            "app_process /system/bin \"\$DROIDANCE_SIDECAR_MAIN\" $width $height $densityDpi " +
            "\"\$DROIDANCE_SIDECAR_PACKAGE\" \"\$DROIDANCE_SIDECAR_PROTOCOL\" \"\$DROIDANCE_SIDECAR_SOURCE\"; " +
            "rc=\$?; echo \"\$(date +%s) supervisor: sidecar exited rc=\$rc\"; " +
            "[ \$rc -eq 0 ] && break; " +
            "sleep $RESTART_DELAY_SECONDS; " +
            "[ -f \"\$CLASSPATH\" ] || break; " +
            (if (stopWhenDeveloperOptionsDisabled) "[ \"\$(settings get global development_settings_enabled 2>/dev/null)\" != 0 ] || break; " else "") +
            "done"
        val logPath = logPath(requireStorageDirectory(storageDirectory, packageName))
        return buildString {
            // 不能在同一条 sh -c 里按 mainClass 清进程，否则会匹配当前命令文本并杀掉启动过程。
            append("export CLASSPATH=${escape(apkPath)}; ")
            append("export DROIDANCE_SIDECAR_MAIN=${escape(mainClass)}; ")
            append("export DROIDANCE_SIDECAR_PACKAGE=${escape(packageName)}; ")
            append("export DROIDANCE_SIDECAR_PROTOCOL=${escape("droidance-sidecar-protocol=$protocolVersion")}; ")
            append("export DROIDANCE_SIDECAR_SOURCE=${escape("droidance-sidecar-source=$apkPath")}; ")
            append("export DROIDANCE_SIDECAR_STORAGE=${escape(storageDirectory)}; ")
            append("mkdir -p ${escape(storageDirectory + "/sidecar")}; ")
            // setsid 让 sidecar 脱离 AIR/AirAdbClient 的一次性 shell 会话，避免 shell 连接关闭时
            // SIGHUP 杀掉后台 sidecar 进程（真机 binder timeout 的疑似主因；模拟器手动 adb shell 不复现）。
            // setsid 脱离一次性 shell 会话 + </dev/null 关闭 stdin 防 SIGPIPE，
            // 确保 AirAdbClient 连接关闭后 sidecar 仍存活到完成 binder 广播。
            // Append so the exit that a restart follows stays readable; a launch rotates a large log.
            append("[ \"\$(wc -c <$logPath 2>/dev/null || echo 0)\" -gt $LOG_ROTATE_BYTES ] && mv -f $logPath $logPath.1; ")
            append("(setsid sh -c ${escape(supervisor)} ")
            append("</dev/null >>$logPath 2>&1 &)")
        }
    }

    /** Signals the whole process group, otherwise the supervisor would restart the sidecar. */
    fun stopCommand(pids: Collection<Int>, force: Boolean, packageName: String): String? {
        require(packagePattern.matches(packageName)) { "invalid sidecar package" }
        val signal = if (force) "-9 " else ""
        return pids.asSequence()
            .filter { pid -> pid > 1 }
            .distinct()
            .joinToString("; ") { pid ->
                "if ps -A -o UID,PID,ARGS | awk -v target='$packageName' -v pid='$pid' " +
                    "'(\$1 == \"shell\" || \$1 == \"2000\") && \$2 == pid && " +
                    "\$5 == \"$MAIN_CLASS\" && \$9 == target { found=1 } END { exit !found }'; then " +
                    "g=\$(cut -d' ' -f5 /proc/$pid/stat 2>/dev/null); " +
                    "if [ \"\${g:-0}\" -gt 1 ] && [ -r \"/proc/\$g/environ\" ] && " +
                    "tr '\\000' '\\n' <\"/proc/\$g/environ\" | grep -Fqx 'DROIDANCE_SIDECAR_PACKAGE=$packageName'; " +
                    "then kill ${signal}-- -\$g 2>/dev/null; fi; kill ${signal}${pid} 2>/dev/null; fi"
            }
            .ifBlank { null }
    }

    /** Read through the live device shell so a debug export shows why the sidecar last stopped. */
    fun evidenceCommand(storageDirectory: String): String =
        "echo adb_enabled=\$(settings get global adb_enabled) " +
            "adb_wifi_enabled=\$(settings get global adb_wifi_enabled) " +
            "adbd=\$(getprop init.svc.adbd) adbd_start_boottime_ns=\$(getprop ro.boottime.adbd) " +
            "tcp_port=\$(getprop service.adb.tcp.port); ps -A -o PID,STIME,ETIME,NAME | grep '[a]dbd$'; " +
            "ps -A -o UID,PID,PPID,PGID,ETIME,ARGS | grep -E '[V]irtualSpaceSidecarMain|[D]ROIDANCE_SIDECAR' | cut -c1-120; " +
            "cat ${logPath(storageDirectory)}.1 2>/dev/null | tail -c 32768; tail -c 65536 ${logPath(storageDirectory)} 2>/dev/null"
    private const val LOG_ROTATE_BYTES = 262144
    private const val RESTART_DELAY_SECONDS = 5
}
