// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

/**
 * app_process entrypoint for the Standard shell sidecar.
 */
public final class VirtualSpaceSidecarMain {
    private static final String TAG = "DroidanceVSpace";
    private static final String MAIN_CLASS_NAME = "com.droidance.core.device.agent.device.VirtualSpaceSidecarMain";
    private static final String PROTOCOL_ARGUMENT_PREFIX = "droidance-sidecar-protocol=";
    private static final String SOURCE_ARGUMENT_PREFIX = "droidance-sidecar-source=";
    private static final int SHELL_UID = 2000;
    private static final long STALE_PROCESS_EXIT_WAIT_MS = 100L;
    private static final int STALE_PROCESS_EXIT_RETRIES = 20;
    private static final long SCREEN_OFF_APP_PROBE_MS = 2000L;
    private static final long SCREEN_STATE_POLL_MS = 1000L;
    private static final int PACKAGE_PROBE_TICKS = 5;
    private static final int RESTARTABLE_FAILURE_EXIT_CODE = 1;
    private static final int SCREEN_OFF = 0;
    private static final int SCREEN_LOCKED = 1;
    private static final int SCREEN_UNLOCKED = 2;
    private static final String LEGACY_SHARED_DIRECTORY = "/data/local/tmp/";
    private static String storageDirectory;
    private static java.io.RandomAccessFile leaseFile;
    private static java.nio.channels.FileLock leaseLock;
    private static Object windowManager;
    private static java.lang.reflect.Method keyguardLockedMethod;
    private static boolean keyguardProbeFailed;

    private VirtualSpaceSidecarMain() {}

    public static void main(String[] args) {
        try {
            ensureSupportedIdentity();
            String requestedStorage = System.getenv("DROIDANCE_SIDECAR_STORAGE");
            writeLog("INFO main entered, uid=" + Process.myUid() + " args=" + args.length, null);
            String codeSource = System.getProperty("java.class.path", "");
            if (args.length != 6 || parseProtocolVersion(args) != VirtualSpaceSidecarService.SIDECAR_PROTOCOL_VERSION
                    || codeSource.isEmpty() || !args[5].equals(SOURCE_ARGUMENT_PREFIX + codeSource)) {
                reportFailure("missing sidecar display args", null);
                return;
            }
            int width = parseInt(args[0], 1);
            int height = parseInt(args[1], 1);
            int densityDpi = parseInt(args[2], 160);
            String targetPackage = args[3];
            if (!targetPackage.matches("[A-Za-z0-9_.]+")) {
                reportFailure("invalid sidecar package", null);
                return;
            }
            try {
                storageDirectory = ResidentSidecarProcessCommands.INSTANCE
                        .requireStorageDirectory(requestedStorage == null ? "" : requestedStorage, targetPackage);
            } catch (IllegalArgumentException e) {
                reportFailure("invalid sidecar storage directory", e);
                return;
            }
            // Retire sidecars of other builds first: a previous build may hold a lease at another path.
            int protocolVersion = parseProtocolVersion(args);
            java.util.List<Integer> stalePids = replaceStaleSidecarProcesses(
                    targetPackage, protocolVersion, codeSource
            );
            if (!acquireProcessLease(targetPackage)) {
                if (!stalePids.isEmpty() && acquireProcessLeaseWithRetry(targetPackage)) {
                    reportInfo(
                            "replaced stale sidecar pids=" + stalePids +
                                    " protocol=" + protocolVersion +
                                    " role=" + "virtual"
                    );
                } else {
                    reportInfo("duplicate sidecar skipped for role=" + "virtual");
                    return;
                }
            }
            removeLegacySharedFiles(targetPackage);
            Looper.prepareMainLooper();
            writeLog("INFO looper prepared", null);
            Context sidecarContext = SidecarShellContext.create();
            writeLog("INFO sidecar context created pkg=" + sidecarContext.getPackageName(), null);
            SidecarPackageIdentity packageIdentity = SidecarPackageIdentity.capture(sidecarContext, targetPackage);
            if (!codeSource.equals(packageIdentity.sourcePath)) {
                reportInfo("sidecar APK no longer matches installed package");
                System.exit(0);
                return;
            }
            VirtualSpaceSidecarService service = new VirtualSpaceSidecarService(sidecarContext, packageIdentity);
            writeLog("INFO service created", null);
            startScreenLifecycle(sidecarContext, service, targetPackage);
            boolean broadcastOk = service.broadcastBinder(sidecarContext, targetPackage);
            writeLog("INFO broadcastBinder returned=" + broadcastOk + " err=" + service.getLastError(), null);
            if (!broadcastOk) {
                reportFailure("broadcastBinder failed: " + service.getLastError(), null);
                System.exit(RESTARTABLE_FAILURE_EXIT_CODE);
            }
            reportInfo("sidecar binder broadcast sent");
            // 持续重发 binder 广播：防 app receiver 第一次未就绪而丢失 binder，
            // 让 app 在轮询窗口内总能收到一个存活的 binder。
            startRebroadcast(service, sidecarContext, targetPackage, packageIdentity);
            Looper.loop();
        } catch (Throwable t) {
            reportFailure(t.getClass().getSimpleName() + ": " + safeMessage(t), t);
            // Exit code 0 tells the supervisor to stop; a crash on the main looper must restart.
            System.exit(RESTARTABLE_FAILURE_EXIT_CODE);
        }
    }

    /**
     * app_process is unknown to ActivityManager, so registerReceiver silently registers nothing.
     * Polling on the main looper needs no system registration and pauses while the device sleeps.
     */
    private static void startScreenLifecycle(
            Context context,
            VirtualSpaceSidecarService service,
            String targetPackage
    ) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int observed = readScreenState(power);
            private int ticks;

            @Override
            public void run() {
                // An upgrade keeps the package installed, so a stale sidecar can still hand over
                // to its replacement; once the package is gone this process has no owner left.
                if (++ticks % PACKAGE_PROBE_TICKS == 0 && !isPackageInstalled(context, targetPackage)) {
                    reportInfo("sidecar package uninstalled");
                    System.exit(0);
                    return;
                }
                try {
                    int current = readScreenState(power);
                    if (current != observed) {
                        observed = current;
                        writeLog("INFO screen state changed to " + current, null);
                        if (current == SCREEN_UNLOCKED) {
                            service.onScreenUnlocked();
                        } else {
                            service.onScreenLocked();
                        }
                        if (current != SCREEN_OFF) service.broadcastBinder(context, targetPackage);
                    }
                } catch (Throwable t) {
                    writeLog("WARN screen state check failed: " + safeMessage(t), t);
                }
                handler.postDelayed(this, SCREEN_STATE_POLL_MS);
            }
        });
        writeLog("INFO screen lifecycle polling started", null);
    }

    private static boolean isPackageInstalled(Context context, String targetPackage) {
        try {
            context.getPackageManager().getApplicationInfo(targetPackage, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            writeLog("WARN package probe failed: " + safeMessage(t), null);
            return true;
        }
    }

    private static int readScreenState(PowerManager power) {
        if (!power.isInteractive()) return SCREEN_OFF;
        return isKeyguardLocked() ? SCREEN_LOCKED : SCREEN_UNLOCKED;
    }

    /** KeyguardManager cannot be constructed in app_process; it needs app shared memory. */
    private static boolean isKeyguardLocked() {
        if (keyguardProbeFailed) return false;
        try {
            if (keyguardLockedMethod == null) {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class)
                        .invoke(null, Context.WINDOW_SERVICE);
                windowManager = Class.forName("android.view.IWindowManager$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, binder);
                keyguardLockedMethod = Class.forName("android.view.IWindowManager")
                        .getMethod("isKeyguardLocked");
            }
            return Boolean.TRUE.equals(keyguardLockedMethod.invoke(windowManager));
        } catch (ReflectiveOperationException | RuntimeException e) {
            keyguardProbeFailed = true;
            writeLog("WARN keyguard state unavailable: " + safeMessage(e), null);
            return false;
        }
    }

    private static void ensureSupportedIdentity() {
        int uid = Process.myUid();
        if ((uid != SHELL_UID)
                || (uid == SHELL_UID && Os.getgid() != SHELL_UID)) {
            throw new IllegalStateException(
                    "unsupported sidecar identity: uid=" + uid + " gid=" + Os.getgid());
        }
    }

    /**
     * Keep one long-lived app_process per app installation for the shell UID. The Android app can be
     * restarted while a resident sidecar deliberately survives; without a process-level lease
     * the restarted app starts another broadcaster whenever the old Binder is not yet in its
     * registry. File locks are released by the kernel when the sidecar exits, so no stale cleanup
     * job or app-specific process lookup is needed.
     */
    private static boolean acquireProcessLease(String targetPackage) {
        try {
            java.io.File directory = new java.io.File(storageDirectory, "sidecar");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("lease directory unavailable");
            leaseFile = new java.io.RandomAccessFile(new java.io.File(directory, "lease.lock"), "rw");
            leaseLock = leaseFile.getChannel().tryLock();
            if (leaseLock != null) return true;
            leaseFile.close();
            leaseFile = null;
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            closeProcessLease();
            return false;
        } catch (Throwable t) {
            closeProcessLease();
            reportFailure("sidecar process lease unavailable: " + safeMessage(t), t);
            return false;
        }
    }

    /** Files earlier builds kept in the shared shell directory, which an uninstall never removes. */
    private static void removeLegacySharedFiles(String targetPackage) {
        String[] names = {
                "droidance_vspace_" + SHELL_UID + "_virtual_" + targetPackage + ".lock",
                "droidance_vspace_" + SHELL_UID + "_virtual.lock",
                "droidance_vspace.log",
                "droidance_vspace.log.1"
        };
        for (String name : names) {
            java.io.File file = new java.io.File(LEGACY_SHARED_DIRECTORY, name);
            if (file.exists() && !file.delete()) writeLog("WARN legacy sidecar file not removed: " + name, null);
        }
    }

    static String storageDirectory() {
        if (storageDirectory == null) throw new IllegalStateException("sidecar storage directory unavailable");
        return storageDirectory;
    }

    /**
     * Replaces a resident process from another APK or protocol before the new process
     * gives up on its lease.  This is intentionally done inside app_process: after the Android
     * app is restarted the AIR shell may not be ready yet, while a same-UID sidecar can still
     * safely terminate its stale sibling without relying on the app Binder registry.
     */
    private static java.util.List<Integer> replaceStaleSidecarProcesses(
            String targetPackage, int protocolVersion, String codeSource
    ) {
        java.util.List<Integer> stalePids = new java.util.ArrayList<>();
        java.lang.Process probe = null;
        try {
            probe = new ProcessBuilder(
                    "/system/bin/ps",
                    "-A",
                    "-o",
                    "UID,PID,ARGS"
            ).redirectErrorStream(true).start();
            if (!probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return stalePids;
            }
            String output = new String(
                    probe.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            );
            for (String line : output.split("\\r?\\n")) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 11 || !isSameSidecarIdentity(fields[0])) continue;
                int pid = parsePositiveInt(fields[1]);
                if (pid <= 1 || pid == android.os.Process.myPid()) continue;
                if (!MAIN_CLASS_NAME.equals(fields[4])) continue;
                if (!targetPackage.equals(fields[8])) continue;
                if (isCurrentBuildCommand(line, protocolVersion, codeSource)) continue;
                if (android.os.Process.myUid() == SHELL_UID) {
                    killProcessGroup(pid, targetPackage);
                    stalePids.add(pid);
                }
            }
        } catch (Throwable t) {
            writeLog("WARN stale sidecar probe failed: " + safeMessage(t), t);
        } finally {
            if (probe != null) {
                try {
                    probe.destroy();
                } catch (Throwable error) {
                    writeLog("WARN stale sidecar process cleanup failed: " + error.getClass().getSimpleName(), null);
                }
            }
        }
        return stalePids;
    }

    /** A stale supervisor must stop with its sidecar, or it would relaunch the previous build. */
    private static void killProcessGroup(int pid, String targetPackage) {
        try {
            int group = processGroupOf(Integer.toString(pid));
            if (group > 1 && group != processGroupOf("self") && processHasPackage(group, targetPackage)) {
                Os.kill(-group, OsConstants.SIGKILL);
            }
        } catch (ErrnoException | java.io.IOException | RuntimeException e) {
            writeLog("WARN stale sidecar group kill failed: " + safeMessage(e), null);
        }
        android.os.Process.killProcess(pid);
    }

    private static boolean processHasPackage(int pid, String targetPackage) throws java.io.IOException {
        String environment = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/" + pid + "/environ")),
                java.nio.charset.StandardCharsets.UTF_8
        );
        for (String value : environment.split("\u0000")) {
            if (value.equals("DROIDANCE_SIDECAR_PACKAGE=" + targetPackage)) return true;
        }
        return false;
    }

    private static int processGroupOf(String process) throws java.io.IOException {
        String stat = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/" + process + "/stat")),
                java.nio.charset.StandardCharsets.UTF_8
        );
        // Fields after the parenthesized name: state, ppid, pgrp.
        return Integer.parseInt(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[2]);
    }

    static boolean isCurrentBuildCommand(String commandLine, int protocolVersion, String codeSource) {
        if (protocolVersion <= 0 || codeSource.isEmpty() || parseProtocolVersion(commandLine) != protocolVersion) return false;
        for (String field : commandLine.trim().split("\\s+")) {
            if (field.equals(SOURCE_ARGUMENT_PREFIX + codeSource)) return true;
        }
        return false;
    }

    private static boolean acquireProcessLeaseWithRetry(String targetPackage) {
        for (int i = 0; i < STALE_PROCESS_EXIT_RETRIES; i++) {
            if (acquireProcessLease(targetPackage)) return true;
            try {
                Thread.sleep(STALE_PROCESS_EXIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isSameSidecarIdentity(String uidField) {
        int uid = parsePositiveInt(uidField);
        return uid == android.os.Process.myUid() &&
                (uid == SHELL_UID);
    }

    private static int parseProtocolVersion(String commandLine) {
        for (String field : commandLine.trim().split("\\s+")) {
            if (!field.startsWith(PROTOCOL_ARGUMENT_PREFIX)) continue;
            return parsePositiveInt(field.substring(PROTOCOL_ARGUMENT_PREFIX.length()));
        }
        return 0;
    }

    private static int parseProtocolVersion(String[] args) {
        if (args == null) return 0;
        for (String arg : args) {
            if (arg == null || !arg.startsWith(PROTOCOL_ARGUMENT_PREFIX)) continue;
            return parsePositiveInt(arg.substring(PROTOCOL_ARGUMENT_PREFIX.length()));
        }
        return 0;
    }

    private static int parsePositiveInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void closeProcessLease() {
        try {
            if (leaseLock != null) leaseLock.release();
        } catch (Throwable t) {
            Log.w(TAG, "sidecar lease release failed", t);
        }
        try {
            if (leaseFile != null) leaseFile.close();
        } catch (Throwable t) {
            Log.w(TAG, "sidecar lease file close failed", t);
        }
        leaseLock = null;
        leaseFile = null;
    }

    private static int readAppPid(String targetPackage) {
        java.lang.Process probe = null;
        try {
            probe = new ProcessBuilder("/system/bin/pidof", targetPackage).redirectErrorStream(true).start();
            if (!probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) return 0;
            String output = new String(
                    probe.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8
            ).trim();
            return output.isEmpty() ? 0 : parsePositiveInt(output.split("\\s+")[0]);
        } catch (Throwable t) {
            writeLog("WARN app pid probe failed: " + safeMessage(t), null);
            return 0;
        } finally {
            if (probe != null) probe.destroyForcibly();
        }
    }

    private static void startRebroadcast(
            VirtualSpaceSidecarService service,
            Context ctx,
            String targetPackage,
            SidecarPackageIdentity packageIdentity
    ) {
        Thread t = new Thread(() -> {
            // 前 15 秒快频(1s)吸收 app receiver 首次未就绪，之后转慢频(5s)长驻。
            // 关键 WiFi 无关性：sidecar 用 setsid 长活，app 进程重启后错过首轮广播也能在数秒内重新收到
            // 一个存活的 binder，无需再经 ADB/无线调试(WiFi 关也成立)。广播是本机 IPC，与网络无关。
            int i = 0;
            PowerManager powerManager = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            int observedAppPid = -1;
            while (true) {
                boolean interactive = powerManager == null || powerManager.isInteractive();
                long sleepMs = i < 15 ? 1000L : interactive ? 5000L : SCREEN_OFF_APP_PROBE_MS;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                }
                SidecarPackageIdentity.State packageState = packageIdentity.state(ctx);
                if (packageState == SidecarPackageIdentity.State.CHANGED) {
                    writeLog("INFO installed package changed; retiring old sidecar", null);
                    service.destroy();
                    return;
                }
                if (packageState == SidecarPackageIdentity.State.UNAVAILABLE) continue;
                // 熄屏时不向挂起的目标应用发送周期广播，防止 OEM 功耗冻结管理器代理队列打满(如 ColorOS bcMax)强制唤醒
                // 但无 ADB 时重启后的 app 只能等这里：熄屏时 app 进程号变化才补发一次，不走周期广播。
                if (powerManager != null && !powerManager.isInteractive()) {
                    int appPid = readAppPid(targetPackage);
                    boolean restarted = observedAppPid >= 0 && appPid > 0 && appPid != observedAppPid;
                    observedAppPid = appPid;
                    if (!restarted) continue;
                } else {
                    observedAppPid = -1;
                }
                try {
                    service.broadcastBinder(ctx, targetPackage);
                } catch (Throwable t1) {
                    writeLog("WARN rebroadcast failed: " + safeMessage(t1), t1);
                }
                i++;
            }
        }, "DroidanceVSpaceRebroadcast");
        t.setDaemon(true);
        t.start();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Math.max(Integer.parseInt(raw), 0);
        } catch (Throwable t) {
            writeLog("WARN bad integer arg: " + raw + ", " + safeMessage(t), t);
            return fallback;
        }
    }

    private static java.io.PrintWriter logWriter;

    /** 直接写文件(像 aladdin)，不依赖 stdout 重定向——realme app_process 的 System.out 走 logcat 不进 >log。 */
    private static synchronized void writeLog(String line, Throwable t) {
        try {
            if (logWriter == null) {
                // Before the storage directory is validated, lines go to logcat only.
                if (storageDirectory == null) {
                    Log.i(TAG, line, t);
                    return;
                }
                logWriter = new java.io.PrintWriter(
                    new java.io.FileWriter(ResidentSidecarProcessCommands.INSTANCE.logPath(storageDirectory), true), true);
            }
            logWriter.println(System.currentTimeMillis() + " " + line);
            if (t != null) t.printStackTrace(logWriter);
        } catch (Throwable t1) {
            Log.w(TAG, "sidecar file log failed: " + safeMessage(t1));
        }
    }

    private static void reportFailure(String message, Throwable t) {
        String detail = message == null || message.isEmpty() ? "unknown sidecar failure" : message;
        Log.e(TAG, detail, t);
        writeLog("FAIL " + detail, t);
    }

    private static void reportInfo(String message) {
        Log.i(TAG, message);
        writeLog("INFO " + message, null);
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "" : message;
    }
}
