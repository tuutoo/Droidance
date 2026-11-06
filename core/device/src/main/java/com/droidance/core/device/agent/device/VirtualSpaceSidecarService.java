// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import com.droidance.core.agent.device.*;

import com.droidance.core.device.agent.device.*;

import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ObjIntConsumer;

/**
 * Binder sidecar that owns the background VirtualDisplay.
 *
 * The paired ADB shell app_process instantiates this class and registers it
 * as the background display binder service.
 */
public final class VirtualSpaceSidecarService extends IVirtualSpaceSidecar.Stub {
    public static final String SERVICE_NAME = "droidance_vspace";
    private static final String DISPLAY_NAME = "Wireless Display";
    private static final String CLIP_LABEL = "text";
    private static final int WAKE_REASON_APPLICATION = 2;
    // PowerManager.SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP：唯一能把虚拟屏所在
    // power group 拉成 interactive 的等级（真机 dumpsys 验证，见 keepDisplayInteractive）。
    private static final int SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a;
    private static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    private static final String DISPLAY_WAKE_LOCK_TAG = "WindowManager-Display";
    private static final String VIEWER_WAKE_LOCK_TAG = "Droidance:VirtualSpaceViewer";
    // 带超时持锁：任务里每个动作都会刷新它，任务结束后自动过期，避免空闲期一直吊着电源组耗电。
    private static final long DISPLAY_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long DISPLAY_INTERACTIVE_SETTLE_MS = 120L;
    private static final int DISPLAY_INTERACTIVE_ATTEMPTS = 3;
    private static final long DISPLAY_INTERACTIVE_REUSE_MS = 800L;
    private static final String INPUT_PATH_REFLECTION = "reflection";
    private static final String INPUT_PATH_INPUT_COMMAND = "input_cmd";
    // 虚拟屏窗口就绪前 injectInputEvent 可能返回 false，做有界重试吸收时序竞态。
    private static final int INJECT_MAX_ATTEMPTS = 4;
    private static final long INJECT_RETRY_DELAY_MS = 120L;
    private static final long TEXT_CLIPBOARD_HANDOFF_MS = 350L;
    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;
    private static final int PREVIEW_MAX_WIDTH = 720;
    private static final int PREVIEW_JPEG_QUALITY = 72;
    public static final int SIDECAR_PROTOCOL_VERSION = 75;
    private static final long MAIN_DIRECT_CAPTURE_TIMEOUT_MS = 1_500L;
    private static final long SHELL_EXEC_TIMEOUT_MS = 8_000L;
    private static final long LIFECYCLE_FRAME_TIMEOUT_MS = 1_500L;
    private static final long LIFECYCLE_FRAME_POLL_MS = 50L;

    private Context context;
    private final SidecarPackageIdentity packageIdentity;
    private volatile SidecarDisplayInsets displayInsets;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread frameThread;
    private int displayId = -1;
    private int width = 0;
    private int height = 0;
    private int densityDpi = 0;
    private volatile FrameLeaseStore<CapturedFrame> frames = new FrameLeaseStore<>(
            () -> {}, error -> logCleanupFailure("close retained frame", error));
    private volatile long latestFrameAtMs = 0L;
    private volatile String status = "NEW";
    private volatile String lastError = "";
    private volatile boolean systemServiceRegistered = false;
    private volatile IRuntimeIndicatorCallback runtimeIndicatorCallback;
    private volatile String runtimePackage;
    private volatile IBinder agentRuntimeOwner;
    private final String runtimeIdentity = java.util.UUID.randomUUID().toString();

    @Override
    public String getRuntimeIdentity() { return runtimeIdentity; }
    private volatile SidecarRuntimeSurface runtimeSurface;
    private final SidecarViewerMirror viewerMirror = new SidecarViewerMirror(this::holdViewerDisplayAwake);
    private SidecarViewerIme viewerIme;
    private boolean manualTouchActive;
    // 每次注入记录走了哪条通道：反射成功和"反射失败静默回退 input 命令"对外消息完全一样，
    // 没有这条证据时 debug 包无法区分"注入到了但被忽略"和"通道降级了"。
    private volatile String lastInputEvidence = "";
    private volatile String lastReflectionFailure = "";
    private volatile String displayWakeEvidence = "";
    private Object displayWakeLock;
    private volatile long lastInteractiveConfirmedAtMs = 0L;

    public VirtualSpaceSidecarService() {
        this(null);
    }

    public VirtualSpaceSidecarService(Context context) {
        this(context, null);
    }

    VirtualSpaceSidecarService(Context context, SidecarPackageIdentity packageIdentity) {
        this.context = context;
        this.packageIdentity = packageIdentity;
    }

    @Override
    public synchronized boolean start(int width, int height, int densityDpi) {
        if (getDisplayId() > Display.DEFAULT_DISPLAY) {
            status = "READY";
            return true;
        }
        releaseDisplay();
        this.width = Math.max(width, 1);
        this.height = Math.max(height, 1);
        this.densityDpi = Math.max(densityDpi, 160);
        status = "STARTING";
        lastError = "";

        try {
            Context systemContext = resolveContext();
            DisplayManager displayManager = (DisplayManager) systemContext.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                fail("DisplayManager unavailable");
                return false;
            }

            HandlerThread thread = new HandlerThread("DisplayFrames");
            thread.start();
            ImageReader imageReader = ImageReader.newInstance(
                    this.width,
                    this.height,
                    PixelFormat.RGBA_8888,
                    5
            );
            FrameLeaseStore<CapturedFrame> stream = new FrameLeaseStore<>(
                    imageReader::close, error -> logCleanupFailure("close retained frame", error));
            frames = stream;
            imageReader.setOnImageAvailableListener(source -> {
                Image image = null;
                try {
                    image = source.acquireLatestImage();
                    if (image == null) return;
                    long atMs = System.currentTimeMillis();
                    stream.publish(new CapturedFrame(image, image.getWidth(), image.getHeight(), atMs));
                    image = null;
                    latestFrameAtMs = atMs;
                } catch (Throwable error) {
                    Log.w("FramePipeline", "Frame acquisition or publication failed", error);
                } finally {
                    if (image != null) {
                        try {
                            image.close();
                        } catch (Throwable error) {
                            logCleanupFailure("close unpublished image", error);
                        }
                    }
                }
            }, new Handler(thread.getLooper()));

            int flags = SidecarPlatformCompat.virtualDisplayFlags(Build.VERSION.SDK_INT);
            VirtualDisplay created = null;
            try {
                created = displayManager.createVirtualDisplay(
                        DISPLAY_NAME,
                        this.width,
                        this.height,
                        this.densityDpi,
                        imageReader.getSurface(),
                        flags
                );
            } catch (Throwable t) {
                if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                    flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
                    created = displayManager.createVirtualDisplay(
                            DISPLAY_NAME,
                            this.width,
                            this.height,
                            this.densityDpi,
                            imageReader.getSurface(),
                            flags
                    );
                } else {
                    throw t;
                }
            }
            if (created == null || created.getDisplay() == null) {
                imageReader.close();
                thread.quitSafely();
                fail("createVirtualDisplay returned null");
                return false;
            }

            frameThread = thread;
            reader = imageReader;
            display = created;
            Display createdDisplay = created.getDisplay();
            displayId = createdDisplay.getDisplayId();
            viewerIme = new SidecarViewerIme(displayId);
            keepDisplayInteractive("virtual display created");
            status = "READY";
            return true;
        } catch (Throwable t) {
            shutdown();
            fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public synchronized Bundle configureDisplayInsets(int targetDisplayId, Bundle geometry) {
        Bundle rejected = new Bundle();
        if (targetDisplayId <= 0 || targetDisplayId != displayId || display == null || geometry == null) {
            rejected.putBoolean("ok", false);
            rejected.putString("detail", "Insets require the active virtual display");
            return rejected;
        }
        try {
            if (displayInsets == null) displayInsets = new SidecarDisplayInsets(resolveContext(), displayId);
            return displayInsets.configure(geometry);
        } catch (Exception error) {
            rejected.putBoolean("ok", false);
            rejected.putString("detail", "Insets unavailable: " + safeMessage(error));
            return rejected;
        }
    }

    @Override
    public synchronized int getDisplayId() {
        Display current = display == null ? null : display.getDisplay();
        return current != null && current.isValid() && current.getDisplayId() == displayId
                ? displayId : -1;
    }

    @Override
    public synchronized Bundle inspectDisplayContent(int requestedDisplayId) {
        Bundle result = new Bundle();
        int currentDisplayId = getDisplayId();
        boolean valid = requestedDisplayId > Display.DEFAULT_DISPLAY && currentDisplayId == requestedDisplayId;
        result.putInt("displayId", currentDisplayId);
        result.putBoolean("displayValid", valid);
        result.putBoolean("querySucceeded", false);
        if (!valid) return result;
        try {
            Object tasks = SidecarDisplayContent.rootTasks(requestedDisplayId);
            int activityCount = SidecarDisplayContent.activityCount(tasks, requestedDisplayId);
            // The platform may remove a display during the read even while this service is locked.
            currentDisplayId = getDisplayId();
            result.putInt("displayId", currentDisplayId);
            result.putBoolean("displayValid", currentDisplayId == requestedDisplayId);
            result.putInt("activityCount", activityCount);
            result.putBoolean("querySucceeded", true);
        } catch (Exception error) {
            result.putString("detail", "Display content query failed: " + safeMessage(error));
        }
        return result;
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }

    @Override
    public String getStatus() {
        String frame = latestFrameAtMs > 0L ? ", frameAtMs=" + latestFrameAtMs : "";
        String wake = displayWakeEvidence.isEmpty() ? "" : ", " + displayWakeEvidence;
        SidecarDisplayInsets insets = displayInsets;
        return status + ", uid=" + Process.myUid() + ", displayId=" + displayId + frame + wake
                + ", viewer_mirror=" + viewerMirror.isAttached()
                + ", insets=" + (insets == null ? "not_configured" : insets.evidence());
    }

    @Override
    public String getLastError() {
        return lastError;
    }

    @Override
    public String getLastInputEvidence() {
        return lastInputEvidence;
    }

    @Override
    public boolean isFrameReady() {
        return frames.hasFrame();
    }

    /**
     * 主动把虚拟屏电源组唤醒，供任务开始时调用。
     *
     * keepDisplayInteractive 原本只有注入路径（acceptsInteractiveDisplay）会顺带触发，
     * 而 DeepLink / 开应用走的是 execShellCommand 转发的 `am start --display N`——sidecar 只是
     * fork 一个 sh，看不懂命令内容，没有任何机会插入唤醒。唤醒锁 10 分钟超时，任务间隔一长
     * 电源组必然 DOZING，于是任务第一步的 am start 返回 Success 却不 resume，harness 截到的
     * 还是上一个任务的残留帧，模型基于过期画面推理并脱靶。锁屏场景必现（设备闲置远超 10 分钟）。
     */
    @Override
    public synchronized boolean ensureDisplayInteractive(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return false;
        if (displayId < 0) return false;
        return keepDisplayInteractive("task start");
    }

    @Override
    public boolean launchPackage(int targetDisplayId, String packageName, String component) {
        try {
            if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
            if (component != null && !component.trim().isEmpty()) {
                return launchActivity(targetDisplayId, component);
            }
            if (packageName == null || packageName.trim().isEmpty()) {
                fail("packageName missing");
                return false;
            }
            PackageManager packageManager = resolveContext().getPackageManager();
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                fail("launch intent missing for " + packageName);
                return false;
            }
            startIntentOnDisplay(resolveContext(), intent, targetDisplayId);
            return true;
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public boolean launchActivity(int targetDisplayId, String component) {
        try {
            if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
            if (component == null || component.trim().isEmpty()) {
                fail("component missing");
                return false;
            }
            ComponentName name = ComponentName.unflattenFromString(component);
            if (name == null) {
                fail("bad component: " + component);
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(name);
            startIntentOnDisplay(resolveContext(), intent, targetDisplayId);
            return true;
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public boolean tap(int targetDisplayId, int x, int y) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        beginRuntimeInputDispatch();
        try {
            // 20260722-V6/V7 的真机日志已验证这条路径可在虚拟屏产生真实画面变化。
            int dur = 55 + (int) (Math.random() * 25);
            if (injectTapSafely(targetDisplayId, x, y, dur)) return true;
            boolean ok = runInputCommand(targetDisplayId, "tap", Integer.toString(x), Integer.toString(y));
            noteInputEvidence("tap", INPUT_PATH_INPUT_COMMAND, ok, reflectionFallbackDetail());
            return ok;
        } finally {
            endRuntimeInputDispatch();
        }
    }

    @Override
    public boolean doubleTap(int targetDisplayId, int x, int y) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        beginRuntimeInputDispatch();
        try {
            int dur1 = 50 + (int) (Math.random() * 20);
            if (!injectTapSafely(targetDisplayId, x, y, dur1)) return false;
            SystemClock.sleep(75 + (int) (Math.random() * 35));
            int dur2 = 50 + (int) (Math.random() * 20);
            return injectTapSafely(targetDisplayId, x, y, dur2);
        } finally {
            endRuntimeInputDispatch();
        }
    }

    @Override
    public boolean longPress(int targetDisplayId, int x, int y, int durationMs) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        beginRuntimeInputDispatch();
        try {
            return injectTapSafely(targetDisplayId, x, y, Math.max(durationMs, 1));
        } finally {
            endRuntimeInputDispatch();
        }
    }

    @Override
    public boolean touch(
            int targetDisplayId,
            int action,
            int x,
            int y,
            long downTimeMs,
            long eventTimeMs) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            return false;
        }
        synchronized (this) {
            boolean completesGesture =
                    action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            try {
                // Independent focus lets the virtual app receive the touch without taking
                // the physical viewer's system Back target. Wait until UP to focus its IME;
                // a system edge gesture ends in CANCEL and must not steal top focus.
                boolean focusEditor = SidecarPlatformCompat.hasOwnDisplayFocus(Build.VERSION.SDK_INT)
                        ? action == MotionEvent.ACTION_UP : action == MotionEvent.ACTION_DOWN;
                if (focusEditor && viewerIme != null) viewerIme.focusEditor();
                if (action == MotionEvent.ACTION_DOWN && manualTouchActive) {
                    manualTouchActive = false;
                    endRuntimeInputDispatch();
                }
                if (!manualTouchActive) {
                    beginRuntimeInputDispatch();
                    manualTouchActive = true;
                }
                long safeDownTime = Math.max(0L, downTimeMs);
                long safeEventTime = Math.max(safeDownTime, eventTimeMs);
                float pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0.0f : 0.72f;
                float size = 0.05f;
                MotionEvent event = MotionEvent.obtain(
                        safeDownTime,
                        safeEventTime,
                        action,
                        x,
                        y,
                        pressure,
                        size,
                        0,
                        1.0f,
                        1.0f,
                        1,
                        0
                );
                try {
                    prepareMotionEvent(event, targetDisplayId);
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (injectInputEventOnce(event, INJECT_INPUT_EVENT_MODE_ASYNC)) return true;
                        noteInputEvidence("touch_move", INPUT_PATH_REFLECTION, false, "injectInputEvent returned false");
                        fail("touch injectInputEvent returned false");
                        return false;
                    }
                    injectInputEventWithRetry(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
                    noteInputEvidence(touchOpName(action), INPUT_PATH_REFLECTION, true, "");
                    return true;
                } finally {
                    event.recycle();
                }
            } catch (Throwable t) {
                if (manualTouchActive) {
                    manualTouchActive = false;
                    endRuntimeInputDispatch();
                }
                String detail = t.getClass().getSimpleName() + ": " + safeMessage(t);
                noteInputEvidence(touchOpName(action), INPUT_PATH_REFLECTION, false, detail);
                fail(detail);
                return false;
            } finally {
                if (completesGesture && manualTouchActive) {
                    manualTouchActive = false;
                    endRuntimeInputDispatch();
                }
                if (action == MotionEvent.ACTION_CANCEL && viewerIme != null) {
                    try { viewerIme.returnInputFocus(); }
                    catch (Exception unavailable) {
                        Log.w("DroidanceVSpace", "Cannot return canceled touch focus: " + safeMessage(unavailable));
                    }
                }
            }
        }
    }

    @Override
    public boolean swipe(int targetDisplayId, int startX, int startY, int endX, int endY, int durationMs) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        int dur = Math.max(durationMs, 1);
        beginRuntimeInputDispatch();
        try {
            injectSwipe(targetDisplayId, startX, startY, endX, endY, dur);
            noteInputEvidence("swipe", INPUT_PATH_REFLECTION, true, "");
            return true;
        } catch (Throwable t) {
            lastReflectionFailure = t.getClass().getSimpleName() + ": " + safeMessage(t);
            boolean ok = runInputCommand(targetDisplayId, "swipe",
                    Integer.toString(startX), Integer.toString(startY),
                    Integer.toString(endX), Integer.toString(endY), Integer.toString(dur));
            noteInputEvidence("swipe", INPUT_PATH_INPUT_COMMAND, ok, reflectionFallbackDetail());
            if (ok) return true;
            fail(lastReflectionFailure);
            return false;
        } finally {
            endRuntimeInputDispatch();
        }
    }

    @Override
    public boolean key(int targetDisplayId, int keycode) {
        if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
        claimInputFocus();
        // 反射注入为主(与阿拉丁一致)；失败再退 input 命令兜底。
        try {
            injectKey(targetDisplayId, keycode);
            noteInputEvidence("key", INPUT_PATH_REFLECTION, true, "");
            return true;
        } catch (Throwable t) {
            lastReflectionFailure = t.getClass().getSimpleName() + ": " + safeMessage(t);
            boolean ok = runInputCommand(targetDisplayId, "keyevent", Integer.toString(keycode));
            noteInputEvidence("key", INPUT_PATH_INPUT_COMMAND, ok, reflectionFallbackDetail());
            if (ok) return true;
            fail(lastReflectionFailure);
            return false;
        } finally {
            releaseInputFocus();
        }
    }

    @Override
    public boolean keyCombo(int targetDisplayId, int[] keycodes) {
        try {
            if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
            if (keycodes == null || keycodes.length == 0) {
                fail("empty key combo");
                return false;
            }
            claimInputFocus();
            injectKeyCombo(targetDisplayId, keycodes);
            return true;
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        } finally {
            releaseInputFocus();
        }
    }

    /**
     * Android 13 and earlier keep one global input focus, so keys for this display are dropped
     * while another display is focused. Android 14 gives the display its own via OWN_FOCUS.
     */
    private void claimInputFocus() {
        if (SidecarPlatformCompat.hasOwnDisplayFocus(Build.VERSION.SDK_INT) || viewerIme == null) return;
        try { viewerIme.focusForInput(); }
        catch (Exception unavailable) { Log.w("DroidanceVSpace", "Cannot focus virtual display: " + safeMessage(unavailable)); }
    }

    private void releaseInputFocus() {
        if (SidecarPlatformCompat.hasOwnDisplayFocus(Build.VERSION.SDK_INT) || viewerIme == null) return;
        try { viewerIme.returnInputFocus(); }
        catch (Exception unavailable) { Log.w("DroidanceVSpace", "Cannot return input focus: " + safeMessage(unavailable)); }
    }

    @Override
    public Bundle text(int targetDisplayId, String text, boolean clearExisting, String expectedFocusTarget, String method) {
        Bundle evidence = emptyTextEvidence();
        try {
            if (!acceptsInteractiveDisplay(targetDisplayId)) {
                evidence.putString("detail", lastError);
                return evidence;
            }
            if (!"shortcut".equals(method) && !"paste_key".equals(method)) {
                evidence.putString("detail", "unsupported text input method");
                return evidence;
            }
            claimInputFocus();
            String focusState = getInputFocusState(targetDisplayId);
            boolean focusConfirmed = focusState != null &&
                    (expectedFocusTarget == null || expectedFocusTarget.equals(focusState));
            evidence.putBoolean("focusConfirmed", focusConfirmed);
            if (!focusConfirmed) {
                evidence.putString("detail", "focused input window unavailable or changed on display " + targetDisplayId);
                return evidence;
            }
            String payload = text == null ? "" : text;
            long started = SystemClock.elapsedRealtime();
            Bundle result = injectTextOnDisplay(targetDisplayId, payload, clearExisting, focusState, method);
            result.putLong("injectionDurationMs", Math.max(0L, SystemClock.elapsedRealtime() - started - result.getLong("readbackDurationMs", 0L)));
            result.putInt("destinationDisplayId", targetDisplayId);
            result.putString("targetBefore", focusState);
            // The readback's final window check already follows paste/copy delivery. Reuse
            // that transaction-local identity; other input paths still perform a fresh check.
            if (!result.containsKey("targetAfter")) result.putString("targetAfter", getInputFocusState(targetDisplayId));
            result.putString("payloadHash", com.droidance.core.agent.tools.TextInputReadback.hash(payload));
            result.putBoolean("replacement", clearExisting);
            return result;
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + safeMessage(t));
            evidence.putString("detail", lastError);
            return evidence;
        } finally {
            releaseInputFocus();
        }
    }

    @Override
    public String getForegroundComponent(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        String dump = shellStdout("dumpsys window displays");
        String component = DisplayForegroundParser.parse(dump, targetDisplayId);
        if (component != null) return component;
        // A vendor dump format must not cost the frame: the task inventory names the same window.
        try { return SidecarDisplayContent.topComponent(SidecarDisplayContent.rootTasks(targetDisplayId)); }
        catch (Exception unavailable) {
            Log.w("FramePipeline", "Foreground query unavailable: " + safeMessage(unavailable));
            return null;
        }
    }

    @Override
    public String getInputFocusState(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        ManagedShellCommands.Result query = shellCommands.executeInputFocus(java.util.UUID.randomUUID().toString(), SHELL_EXEC_TIMEOUT_MS);
        if (query.exitCode != 0 || query.diagnosticCode != null || query.stdoutTruncated ||
                query.systemOutput != null && query.systemOutput.diagnosticCode != null) return null;
        return InputWindowFocusParser.parse(query.stdout, targetDisplayId).target;
    }

    private static final class FocusQuery {
        final long createdAt = SystemClock.elapsedRealtime();
        boolean canceled;
        String childToken;
    }
    private final java.util.concurrent.ConcurrentHashMap<String, FocusQuery> focusQueries = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Bundle awaitInputFocus(int targetDisplayId, long timeoutMs, String executionToken) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(1L, Math.min(timeoutMs, 5000L));
        Bundle result = new Bundle();
        if (executionToken == null || !executionToken.matches("[a-zA-Z0-9_-]{1,64}")) {
            result.putString("diagnostic", "INPUT_FOCUS_INVALID_TOKEN");
            return result;
        }
        claimInputFocus();
        focusQueries.entrySet().removeIf(entry -> started - entry.getValue().createdAt > 300000L);
        FocusQuery request = focusQueries.computeIfAbsent(executionToken, ignored -> new FocusQuery());
        String diagnostic = "INPUT_CONNECTION_NOT_READY";
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                String childToken;
                synchronized (request) {
                    if (request.canceled) { diagnostic = "INPUT_FOCUS_CANCELED"; break; }
                    childToken = java.util.UUID.randomUUID().toString();
                    request.childToken = childToken;
                }
                if (!acceptsDisplay(targetDisplayId)) { diagnostic = "INPUT_DISPLAY_UNAVAILABLE"; break; }
                ManagedShellCommands.Result query = shellCommands.executeInputFocus(childToken,
                        Math.max(1L, deadline - SystemClock.elapsedRealtime()));
                if (query.systemOutput != null) {
                    result.putLong("queryOriginalChars", query.systemOutput.originalChars);
                    result.putLong("querySelectedChars", query.systemOutput.selectedChars);
                }
                result.putInt("queryAttempts", result.getInt("queryAttempts", 0) + 1);
                result.putInt("queryExitCode", query.exitCode);
                result.putString("queryDiagnosticCode", query.diagnosticCode != null ? query.diagnosticCode :
                        query.systemOutput == null ? null : query.systemOutput.diagnosticCode);
                result.putString("queryExecutionStatus", query.executionStatus);
                result.putBoolean("queryOutputTruncated", query.stdoutTruncated);
                result.putString("queryStderr", query.stderr.substring(0, Math.min(512, query.stderr.length())));
                synchronized (request) {
                    if (request.canceled) { diagnostic = "INPUT_FOCUS_CANCELED"; break; }
                }
                if (query.exitCode != 0 || query.diagnosticCode != null || query.stdoutTruncated ||
                        query.systemOutput != null && query.systemOutput.diagnosticCode != null) {
                    diagnostic = InputFocusQueryStatus.diagnostic(query);
                    break;
                }
                InputWindowFocusParser.Result focus = InputWindowFocusParser.parse(query.stdout, targetDisplayId);
                diagnostic = focus.diagnostic;
                result.putLong("queryCapturedElapsedMs", SystemClock.elapsedRealtime());
                result.putInt("requestedDisplayId", targetDisplayId);
                result.putString("targetKind", "display_window");
                result.putString("focusedWindowName", focus.windowName);
                if (focus.target != null) {
                    result.putString("target", focus.target);
                    break;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { Thread.sleep(Math.min(50L, remaining)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); diagnostic = "INPUT_FOCUS_CANCELED"; break;
                }
            }
        } finally {
            synchronized (request) {
                request.canceled = true;
                if (request.childToken != null) shellCommands.cancel(request.childToken);
            }
        }
        result.putString("diagnostic", diagnostic);
        result.putLong("elapsedMs", SystemClock.elapsedRealtime() - started);
        return result;
    }

    @Override
    public byte[] capturePng(int targetDisplayId) {
        Bundle frame = capturePngFrame(targetDisplayId);
        return frame == null ? null : frame.getByteArray("png");
    }

    @Override
    public Bundle capturePngFrame(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        long started = SystemClock.elapsedRealtime();
        try (FrameLeaseStore.Lease<CapturedFrame> lease = frames.acquire()) {
            if (lease == null) return null;
            CapturedFrame frame = lease.value();
            long acquired = SystemClock.elapsedRealtime();
            Bundle result = new Bundle();
            result.putByteArray("png", frame.png());
            result.putLong("frameAtMs", frame.atMs);
            logFrameTiming("png", started, acquired);
            return result;
        } catch (Exception error) {
            fail("capturePngFrame failed: " + safeMessage(error));
            return null;
        }
    }

    @Override
    public byte[] capturePreviewJpeg(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        long started = SystemClock.elapsedRealtime();
        try (FrameLeaseStore.Lease<CapturedFrame> lease = frames.acquire()) {
            if (lease == null) return null;
            long acquired = SystemClock.elapsedRealtime();
            byte[] result = lease.value().preview();
            logFrameTiming("preview", started, acquired);
            return result;
        } catch (Exception error) {
            fail("capturePreviewJpeg failed: " + safeMessage(error));
            return null;
        }
    }

    @Override
    public Bundle capturePreviewFrame(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        try (FrameLeaseStore.Lease<CapturedFrame> lease = frames.acquire()) {
            if (lease == null) return null;
            CapturedFrame frame = lease.value();
            Bundle result = new Bundle();
            result.putByteArray("jpeg", frame.preview());
            result.putLong("frameAtMs", frame.atMs);
            return result;
        } catch (Exception error) {
            fail("capturePreviewFrame failed: " + safeMessage(error));
            return null;
        }
    }

    @Override
    public Bundle sampleFrame(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        // Rendering readiness must sample the same display source as decision captures.
        // A static display need not publish another asynchronous preview Image.
        Bitmap bitmap = null;
        try {
            bitmap = captureDisplayBitmap(targetDisplayId);
            int side = 64, width = bitmap.getWidth(), height = bitmap.getHeight();
            byte[] pixels = new byte[side * side];
            for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                int px = Math.min(width - 1, (2 * x + 1) * width / (2 * side));
                int py = Math.min(height - 1, (2 * y + 1) * height / (2 * side));
                int color = bitmap.getPixel(px, py);
                pixels[y * side + x] = (byte) ((77 * ((color >> 16) & 255) +
                        150 * ((color >> 8) & 255) + 29 * (color & 255)) >> 8);
            }
            Bundle result = new Bundle();
            result.putByteArray("pixels", pixels);
            result.putLong("frameId", SystemClock.elapsedRealtimeNanos());
            result.putBoolean("freshSnapshot", true);
            return result;
        } catch (Exception error) {
            fail("sampleFrame failed: " + safeMessage(error));
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static void logFrameTiming(String kind, long started, long acquired) {
        Log.d("FramePipeline", "kind=" + kind + " lease_ms=" + (acquired - started)
                + " encode_ms=" + (SystemClock.elapsedRealtime() - acquired));
    }

    /** Foreground queries run beside capture and encoding instead of before and after them. */
    private static final java.util.concurrent.ExecutorService FOREGROUND_QUERIES =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "droidance-foreground-query");
                thread.setDaemon(true);
                return thread;
            });
    /** Decision frames are JPEG: the model receives JPEG and lossless PNG encoding cost ~0.6 s per frame. */
    private static final int FRESH_FRAME_JPEG_QUALITY = 90;

    /** Decision captures bypass the asynchronous preview stream entirely. */
    @Override
    public NativeScreenFrame captureFreshFrame(int targetDisplayId, int sampleSide) {
        if (!acceptsDisplay(targetDisplayId)) return null;
        if (sampleSide < 2 || sampleSide > 64) return null;
        java.util.concurrent.Future<String> beforeQuery = null;
        java.util.concurrent.Future<String> afterQuery = null;
        try {
            long started = SystemClock.elapsedRealtime();
            // The window query that brackets the capture starts with it; a window change between
            // the two queries still rejects the frame.
            beforeQuery = FOREGROUND_QUERIES.submit(() -> getForegroundComponent(targetDisplayId));
            long foregroundBefore = SystemClock.elapsedRealtime();
            // Use the request boundary so encoding cannot make an older capture look younger.
            long capturedAt = System.currentTimeMillis();
            Bitmap bitmap = captureDisplayBitmap(targetDisplayId);
            long captured = SystemClock.elapsedRealtime();
            afterQuery = FOREGROUND_QUERIES.submit(() -> getForegroundComponent(targetDisplayId));
            byte[] encodedFrame;
            int[] colors = new int[sampleSide * sampleSide];
            int[] coordinates = new int[colors.length * 2];
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            try {
                for (int row = 0; row < sampleSide; row++) for (int col = 0; col < sampleSide; col++) {
                    int i = row * sampleSide + col;
                    int nx = (2 * col + 1) * 1000 / (2 * sampleSide);
                    int ny = (2 * row + 1) * 1000 / (2 * sampleSide);
                    int x = Math.min(width - 1, Math.round(nx * width / 1000f));
                    int y = Math.min(height - 1, Math.round(ny * height / 1000f));
                    coordinates[2*i] = x; coordinates[2*i+1] = y;
                    colors[i] = bitmap.getPixel(x, y);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, FRESH_FRAME_JPEG_QUALITY, output)) return null;
                encodedFrame = output.toByteArray();
            } finally { bitmap.recycle(); }
            long encoded = SystemClock.elapsedRealtime();
            String before = beforeQuery.get(SHELL_EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String after = afterQuery.get(SHELL_EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long finished = SystemClock.elapsedRealtime();
            if (!java.util.Objects.equals(before, after)) {
                lastError = "OBSERVATION_WINDOW_CHANGED";
                return null;
            }
            Bundle result = new Bundle();
            result.putIntArray("referenceColors", colors);
            result.putIntArray("referenceCoordinates", coordinates);
            result.putInt("referenceWidth", width); result.putInt("referenceHeight", height);
            result.putLong("frameAtMs", capturedAt);
            result.putString("foregroundComponent", after);
            // Only the wait left after encoding counts: the queries otherwise overlap the capture.
            result.putLong("foregroundMs", finished - encoded);
            result.putLong("captureMs", captured - foregroundBefore);
            result.putLong("encodeMs", encoded - captured);
            return NativeScreenFrame.fromEncodedImage(encodedFrame, result);
        } catch (Throwable error) {
            if (beforeQuery != null) beforeQuery.cancel(true);
            if (afterQuery != null) afterQuery.cancel(true);
            fail("captureFreshFrame failed: " + deepestCauseMessage(error));
            return null;
        }
    }

    private static final class CapturedFrame implements AutoCloseable {
        final Image image;
        final int width, height;
        final long atMs;
        private final Object pngLock = new Object(), previewLock = new Object();
        private byte[] png, preview;
        CapturedFrame(Image image, int width, int height, long atMs) {
            this.image = image; this.width = width; this.height = height; this.atMs = atMs;
        }
        byte[] png() throws Exception {
            synchronized (pngLock) {
                if (png == null) png = encodeImageToPng(image, width, height);
                return png;
            }
        }
        Bitmap bitmap() {
            return imageToBitmap(image, width, height);
        }
        byte[] preview() throws Exception {
            synchronized (previewLock) {
                if (preview == null) preview = encodeImageToJpeg(image, width, height, PREVIEW_MAX_WIDTH, PREVIEW_JPEG_QUALITY);
                return preview;
            }
        }
        @Override public void close() { image.close(); }
    }

    @Override
    public int getProtocolVersion() {
        return SIDECAR_PROTOCOL_VERSION;
    }

    @Override
    public String getCodeSourcePath() {
        return System.getenv("CLASSPATH");
    }

    @Override
    public synchronized boolean attachViewerMirror(int targetDisplayId, SurfaceControl parent,
            int viewWidth, int viewHeight, String owner, IBinder lifetime) {
        try {
            if (!acceptsInteractiveDisplay(targetDisplayId)) return false;
            viewerMirror.attach(targetDisplayId, parent, width, height, viewWidth, viewHeight, owner, lifetime);
            Log.i("ViewerMirror", "attached display=" + targetDisplayId + " target_fps=60 owner=" + owner);
            return true;
        } catch (Exception | LinkageError error) {
            fail("Viewer mirror failed: " + safeMessage(error));
            return false;
        } finally {
            if (parent != null) parent.release();
        }
    }

    @Override
    public synchronized boolean detachViewerMirror(int targetDisplayId, String owner) {
        if (!acceptsDisplay(targetDisplayId)) return false;
        boolean imeHidden = setViewerImeEnabled(targetDisplayId, false, owner, null);
        boolean before = viewerMirror.isAttached();
        boolean matched = viewerMirror.detach(owner);
        Log.i("ViewerMirror", "detach accepted display=" + targetDisplayId + " owner=" + owner
                + " ownerMatched=" + matched + " stateChanged=" + (before != viewerMirror.isAttached())
                + " attached=" + viewerMirror.isAttached());
        return imeHidden;
    }

    @Override
    public synchronized boolean setViewerImeEnabled(int targetDisplayId, boolean enabled, String owner, IBinder lifetime) {
        if (!acceptsDisplay(targetDisplayId) || viewerIme == null) return false;
        if (enabled && !viewerMirror.isOwnedBy(owner)) return false;
        try {
            viewerIme.setEnabled(enabled, owner, lifetime);
            Log.i("ViewerIme", "display=" + targetDisplayId + " enabled=" + enabled + " owner=" + owner);
            return true;
        } catch (Exception error) {
            Log.w("ViewerIme", "Viewer IME policy unavailable", error);
            return false;
        }
    }

    @Override
    public synchronized int getViewerImeBottomInset(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId) || viewerIme == null) return -1;
        try { return viewerIme.bottomInset(); }
        catch (Exception error) {
            Log.w("ViewerIme", "Viewer IME insets unavailable", error);
            return -1;
        }
    }

    @Override
    public synchronized boolean endViewerInput(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId) || viewerIme == null) return false;
        try { viewerIme.close(); return true; }
        catch (Exception error) {
            Log.w("ViewerIme", "Cannot restore execution IME policy", error);
            return false;
        }
    }

    @Override
    public synchronized boolean setExecutionImeEnabled(int targetDisplayId, boolean enabled, String owner, IBinder lifetime) {
        if (!acceptsInteractiveDisplay(targetDisplayId) || viewerIme == null) return false;
        try { viewerIme.setEnabled(enabled, owner, lifetime); return true; }
        catch (Exception error) {
            fail("Execution IME policy unavailable: " + safeMessage(error));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeIndicator(
            boolean visible,
            String label,
            String actionLabel,
            boolean paused
    ) {
        try {
            return runtimeSurface().updateIndicator(visible, label, actionLabel, paused);
        } catch (Throwable t) {
            fail("runtime indicator failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeIsland(
            boolean visible,
            String progressText,
            String currentActionText,
            String takeoverLabel,
            String stopLabel,
            boolean paused
    ) {
        try {
            return runtimeSurface().updateIsland(
                    visible,
                    progressText,
                    currentActionText,
                    takeoverLabel,
                    stopLabel,
                    paused
            );
        } catch (Throwable t) {
            fail("runtime island failed: " + deepestCauseMessage(t));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeIslandBitmap(
            boolean visible,
            byte[] islandPng,
            int topOffsetPx
    ) {
        try {
            return runtimeSurface().updateIslandBitmap(visible, islandPng, topOffsetPx);
        } catch (Throwable t) {
            fail("runtime island bitmap failed: " + deepestCauseMessage(t));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeCursor(
            boolean visible,
            int actionType,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs
    ) {
        try {
            return runtimeSurface().updateCursor(
                    visible,
                    actionType,
                    startX,
                    startY,
                    endX,
                    endY,
                    durationMs
            );
        } catch (Throwable t) {
            fail("runtime cursor failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeCursorBitmap(
            boolean visible,
            int actionType,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            long startedAtMs,
            long finishedAtMs,
            boolean motionEnabled,
            byte[] pointerPng
    ) {
        try {
            return runtimeSurface().updateCursorBitmap(
                    Display.DEFAULT_DISPLAY,
                    visible,
                    actionType,
                    startX,
                    startY,
                    endX,
                    endY,
                    durationMs,
                    startedAtMs,
                    finishedAtMs,
                    motionEnabled,
                    pointerPng
            );
        } catch (Throwable t) {
            fail("runtime cursor bitmap failed: " + deepestCauseMessage(t));
            return false;
        }
    }

    @Override
    public boolean updateRuntimeCursorBitmapOnDisplay(
            int targetDisplayId,
            boolean visible,
            int actionType,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            long startedAtMs,
            long finishedAtMs,
            boolean motionEnabled,
            byte[] pointerPng
    ) {
        try {
            if (visible && !acceptsDisplay(targetDisplayId)) return false;
            return runtimeSurface().updateCursorBitmap(
                    targetDisplayId,
                    visible,
                    actionType,
                    startX,
                    startY,
                    endX,
                    endY,
                    durationMs,
                    startedAtMs,
                    finishedAtMs,
                    motionEnabled,
                    pointerPng
            );
        } catch (Throwable t) {
            fail("runtime cursor bitmap on display failed: " + deepestCauseMessage(t));
            return false;
        }
    }

    @Override
    public SurfaceControl getRuntimeIslandInputSurface() {
        try {
            return runtimeSurface().copyIndicatorInputSurface();
        } catch (Throwable t) {
            fail("runtime island input surface failed: " + deepestCauseMessage(t));
            return null;
        }
    }

    @Override
    public SurfaceControl getRuntimeIslandSurface() {
        try {
            return runtimeSurface().copyIndicatorSurface();
        } catch (Throwable t) {
            fail("runtime island surface failed: " + deepestCauseMessage(t));
            return null;
        }
    }

    @Override
    public boolean updateRuntimeEdgeLight(boolean visible, float density, float[] cornerRadii, boolean motionEnabled) {
        try {
            return runtimeSurface().updateEdgeLight(visible, density, cornerRadii, motionEnabled);
        } catch (Throwable t) {
            fail("runtime edge light failed: " + deepestCauseMessage(t));
            return false;
        }
    }

    @Override
    public boolean setRuntimeSurfaceSuppressed(boolean suppressed) {
        try {
            return runtimeSurface().setSuppressed(suppressed);
        } catch (Throwable t) {
            fail("runtime surface suppression failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    @Override
    public void setRuntimeIndicatorCallback(IRuntimeIndicatorCallback callback) {
        runtimeIndicatorCallback = callback;
    }

    @Override
    public synchronized boolean onScreenLocked() {
        return keepDisplaySurfaceAttached("screen locked");
    }

    @Override
    public synchronized boolean onScreenUnlocked() {
        return keepDisplaySurfaceAttached("screen unlocked");
    }

    private boolean keepDisplaySurfaceAttached(String reason) {
        if (display == null || display.getDisplay() == null || reader == null) {
            lastError = reason + ": virtual display unavailable";
            return false;
        }
        try {
            if (displayId > 0) {
                keepDisplayInteractive(reason);
            }
            long previousFrameAtMs = latestFrameAtMs;
            status = "RECOVERING";
            android.view.Surface targetSurface = reader.getSurface();
            if (targetSurface == null || !targetSurface.isValid()) {
                display.setSurface(null);
                display.setSurface(reader.getSurface());
            } else {
                display.setSurface(targetSurface);
            }
            boolean isLockedReason = "screen locked".equals(reason);
            long timeout = isLockedReason ? 50L : LIFECYCLE_FRAME_TIMEOUT_MS;
            if (waitForFreshFrame(previousFrameAtMs, timeout) || isFrameReady() || isLockedReason) {
                status = "READY";
                lastError = "";
                return true;
            }
            status = "STALE";
            lastError = reason + ": no fresh virtual-display frame after surface reattach";
            return false;
        } catch (Throwable t) {
            fail(reason + ": " + t.getClass().getSimpleName() + ": " + safeMessage(t));
            return false;
        }
    }

    private boolean waitForFreshFrame(long previousFrameAtMs, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(timeoutMs, 1L);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (latestFrameAtMs > previousFrameAtMs && isFrameReady()) return true;
            SystemClock.sleep(LIFECYCLE_FRAME_POLL_MS);
        }
        return latestFrameAtMs > previousFrameAtMs && isFrameReady();
    }

    @Override
    public Bundle captureFreshSample(int targetDisplayId, int[] coordinates) {
        if (!acceptsDisplay(targetDisplayId) || coordinates == null || coordinates.length == 0
                || coordinates.length > 8192 || coordinates.length % 2 != 0) return null;
        DisplayManager manager;
        try { manager = (DisplayManager) resolveContext().getSystemService(Context.DISPLAY_SERVICE); }
        catch (Exception unavailable) { fail("display context unavailable"); return null; }
        Display display = manager == null ? null : manager.getDisplay(targetDisplayId);
        if (display == null) return null;
        int rotation = display.getRotation();
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        try {
            Bitmap bitmap = captureDisplayBitmap(targetDisplayId);
            try {
                int[] colors = new int[coordinates.length / 2];
                for (int i = 0; i < colors.length; i++) {
                    int x = coordinates[2*i], y = coordinates[2*i+1];
                    if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) return null;
                    colors[i] = bitmap.getPixel(x, y);
                }
                Display after = manager.getDisplay(targetDisplayId);
                if (after == null || after.getRotation() != rotation || !acceptsDisplay(targetDisplayId)) return null;
                Bundle result = new Bundle();
                result.putIntArray("colors", colors);
                result.putInt("width", bitmap.getWidth()); result.putInt("height", bitmap.getHeight());
                result.putInt("displayId", targetDisplayId); result.putInt("rotation", rotation * 90);
                result.putLong("capturedAtMs", wall); result.putLong("capturedElapsedMs", elapsed);
                return result;
            } finally { bitmap.recycle(); }
        } catch (Throwable error) { fail("captureFreshSample failed: " + safeMessage(error)); return null; }
    }

    /**
     * WindowManager can capture on demand from Android 14. Earlier releases and vendor variants
     * read the display's own stream instead; its newest frame stays current on a static screen.
     */
    private Bitmap captureDisplayBitmap(int targetDisplayId) throws Exception {
        try {
            return captureComposedBitmap(targetDisplayId);
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception unavailable) {
            Log.w("FramePipeline", "WindowManager capture unavailable: " + deepestCauseMessage(unavailable));
            return captureStreamBitmap();
        }
    }

    private Bitmap captureStreamBitmap() throws Exception {
        try (FrameLeaseStore.Lease<CapturedFrame> lease = frames.acquire()) {
            if (lease == null) throw new IllegalStateException("display stream has no frame");
            return lease.value().bitmap();
        }
    }

    private Bitmap captureComposedBitmap(int targetDisplayId) throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
        IBinder windowBinder = (IBinder) getService.invoke(null, Context.WINDOW_SERVICE);
        if (windowBinder == null) {
            throw new IllegalStateException("window service unavailable");
        }

        Class<?> windowManagerStubClass = Class.forName("android.view.IWindowManager$Stub");
        Object windowManager = windowManagerStubClass
                .getDeclaredMethod("asInterface", IBinder.class)
                .invoke(null, windowBinder);
        Class<?> windowManagerClass = Class.forName("android.view.IWindowManager");
        Class<?> captureArgsClass = Class.forName("android.window.ScreenCapture$CaptureArgs");
        Class<?> captureArgsBuilderClass = Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
        Class<?> listenerClass = Class.forName("android.window.ScreenCapture$ScreenCaptureListener");

        Object captureArgsBuilder = captureArgsBuilderClass.getDeclaredConstructor().newInstance();
        Object captureArgs = captureArgsBuilderClass.getMethod("build").invoke(captureArgsBuilder);
        CountDownLatch callbackReceived = new CountDownLatch(1);
        AtomicReference<Object> screenshotRef = new AtomicReference<>();
        AtomicInteger captureStatus = new AtomicInteger(Integer.MIN_VALUE);
        ObjIntConsumer<Object> callback = (screenshot, statusCode) -> {
            screenshotRef.set(screenshot);
            captureStatus.set(statusCode);
            callbackReceived.countDown();
        };
        Object listener = SidecarPlatformCompat.newCaptureListener(listenerClass, callback);

        Method captureDisplay = windowManagerClass.getMethod(
                "captureDisplay",
                int.class,
                captureArgsClass,
                listenerClass);
        captureDisplay.invoke(windowManager, targetDisplayId, captureArgs, listener);
        if (!callbackReceived.await(MAIN_DIRECT_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("WindowManager capture callback timeout");
        }
        if (captureStatus.get() != 0 || screenshotRef.get() == null) {
            throw new IllegalStateException("WindowManager capture failed, status=" + captureStatus.get());
        }

        Object screenshot = screenshotRef.get();
        HardwareBuffer hardwareBuffer = (HardwareBuffer) screenshot.getClass()
                .getMethod("getHardwareBuffer")
                .invoke(screenshot);
        if (hardwareBuffer == null) {
            throw new IllegalStateException("WindowManager capture returned null HardwareBuffer");
        }

        Bitmap hardwareBitmap = null;
        Bitmap softwareBitmap = null;
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null);
            if (hardwareBitmap == null) {
                throw new IllegalStateException("HardwareBuffer could not be wrapped as Bitmap");
            }
            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (softwareBitmap == null) {
                throw new IllegalStateException("Hardware Bitmap could not be copied for PNG encoding");
            }
            Bitmap result = softwareBitmap;
            softwareBitmap = null;
            return result;
        } finally {
            if (softwareBitmap != null) softwareBitmap.recycle();
            if (hardwareBitmap != null) hardwareBitmap.recycle();
            hardwareBuffer.close();
        }
    }





    private static byte[] readAllBytes(File file) {
        if (!file.exists() || file.length() <= 0) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksLikePng(byte[] bytes) {
        return bytes != null && bytes.length >= 8 &&
                (bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50 &&
                (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47 &&
                (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A &&
                (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A;
    }

    /**
     * 本机执行一条 shell 命令（用于 am start 打开 App，绕开 ADB/AIR 实时连接依赖，WiFi 关闭也能用）。
     * 走 /system/bin/sh -c 而不是进程内 startActivity：sidecar 是 shell 身份(uid=2000)进程，
     * 进程内 startActivity 的 callingPackage 会取自 base context(=android)，被 AMS 拒绝；
     * 真正 fork 一个 sh 子进程运行 am，calling package 才会正确解析成 com.android.shell。
     * Returns a typed Bundle; arbitrary command output cannot alter the transport fields.
     */
    private final ManagedShellCommands shellCommands = new ManagedShellCommands();

    @Override
    public Bundle execShellCommand(String command, long timeoutMs, String executionToken) {
        if (command == null || command.trim().isEmpty()) return null;
        ManagedShellCommands.Result result = shellCommands.execute(executionToken, command, timeoutMs);
        Bundle reply = new Bundle();
        reply.putInt("exit_code", result.exitCode);
        reply.putString("stdout", result.stdout); reply.putString("stderr", result.stderr);
        reply.putString("execution_status", result.executionStatus); reply.putString("diagnostic_code", result.diagnosticCode);
        reply.putBoolean("stdout_truncated", result.stdoutTruncated); reply.putBoolean("stderr_truncated", result.stderrTruncated);
        reply.putLong("stdout_bytes", result.stdoutBytes); reply.putLong("stderr_bytes", result.stderrBytes);
        reply.putInt("uid", android.os.Process.myUid());
        return reply;
    }

    @Override
    public Bundle querySystem(String serviceName, String queryKind, String lineFilter, int contextLines, String executionToken) {
        SystemStateQuery request = new SystemStateQuery(serviceName, SystemQueryKind.valueOf(queryKind), lineFilter, contextLines);
        ManagedShellCommands.Result result = shellCommands.executeSystem(executionToken, request, 10000);
        Bundle reply = new Bundle();
        reply.putInt("exit_code", result.exitCode);
        reply.putString("stdout", result.stdout);
        reply.putString("stderr", result.stderr);
        if (result.systemOutput != null) {
            SystemQueryOutput.Result output = result.systemOutput;
            reply.putLong("original_chars", output.originalChars);
            reply.putLong("selected_chars", output.selectedChars);
            reply.putInt("original_lines", output.originalLines);
            reply.putInt("selected_lines", output.selectedLines);
            reply.putBoolean("truncated", output.truncated);
            reply.putString("diagnostic_code", output.diagnosticCode);
        }
        return reply;
    }

    /** Reads the existing primary clip once without selecting, copying, writing or coercing it. */
    @Override
    public Bundle readClipboard() {
        Bundle reply = new Bundle();
        reply.putInt("reader_uid", Process.myUid());
        // Android assigns each user a 100000-wide UID range, as in the app inventory reader.
        reply.putInt("reader_user_id", Process.myUid() / 100000);
        reply.putString("status", "unknown");
        try {
            Context readerContext = resolveContext();
            // SidecarShellContext uses this regular clipboard device, independently of displayId.
            reply.putInt("clipboard_requested_device_id", Context.DEVICE_ID_DEFAULT);
            ClipboardManager clipboard = (ClipboardManager) readerContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                reply.putString("diagnostic_code", "CLIPBOARD_SERVICE_UNAVAILABLE");
                return reply;
            }
            ClipData clip = clipboard.getPrimaryClip();
            reply.putLong("captured_at_ms", System.currentTimeMillis());
            reply.putLong("captured_elapsed_ms", SystemClock.elapsedRealtime());
            if (clip == null) {
                // The platform also returns null for denied access or a locked device.
                reply.putString("diagnostic_code", "CLIPBOARD_NO_READABLE_CLIP");
                return reply;
            }
            if (clip.getDescription() != null) {
                reply.putLong("clip_timestamp_ms", clip.getDescription().getTimestamp());
                CharSequence label = clip.getDescription().getLabel();
                reply.putString("label", label == null ? null : label.toString());
                java.util.ArrayList<String> mimeTypes = new java.util.ArrayList<>();
                for (int index = 0; index < clip.getDescription().getMimeTypeCount(); index++) {
                    mimeTypes.add(clip.getDescription().getMimeType(index));
                }
                reply.putStringArrayList("mime_types", mimeTypes);
            }
            java.util.ArrayList<Bundle> items = new java.util.ArrayList<>();
            for (int index = 0; index < clip.getItemCount(); index++) {
                ClipData.Item item = clip.getItemAt(index);
                Bundle facts = new Bundle();
                facts.putInt("index", index);
                CharSequence text = item.getText();
                facts.putString("text", text == null ? null : text.toString());
                facts.putBoolean("has_uri", item.getUri() != null);
                facts.putBoolean("has_intent", item.getIntent() != null);
                items.add(facts);
            }
            reply.putInt("item_count", clip.getItemCount());
            reply.putParcelableArrayList("items", items);
            reply.putString("status", "readable");
        } catch (SecurityException denied) {
            reply.putString("diagnostic_code", "CLIPBOARD_READ_DENIED");
        } catch (Exception unavailable) {
            reply.putString("diagnostic_code", "CLIPBOARD_READ_FAILED");
        }
        return reply;
    }

    @Override
    public void cancelShellCommand(String executionToken) {
        shellCommands.cancel(executionToken);
    }

    @Override
    public void cancelInputFocus(String executionToken) {
        if (executionToken == null || !executionToken.matches("[a-zA-Z0-9_-]{1,64}")) return;
        FocusQuery request = focusQueries.computeIfAbsent(executionToken, ignored -> new FocusQuery());
        synchronized (request) {
            request.canceled = true;
            if (request.childToken != null) shellCommands.cancel(request.childToken);
        }
    }

    private String shellStdout(String command) {
        Bundle reply = execShellCommand(command, SHELL_EXEC_TIMEOUT_MS, java.util.UUID.randomUUID().toString());
        return reply == null ? "" : reply.getString("stdout", "");
    }

    @Override
    public synchronized boolean shutdown() {
        if (manualTouchActive) {
            manualTouchActive = false;
            endRuntimeInputDispatch();
        }
        if (runtimeSurface != null) {
            runtimeSurface.destroy();
            runtimeSurface = null;
        }
        return releaseDisplay();
    }

    /** 只回收虚拟屏本身（含唤醒锁与帧通道），不动主屏的运行时浮层。 */
    private synchronized boolean releaseDisplay() {
        if (viewerIme != null) {
            try { viewerIme.close(); }
            catch (Exception error) { logCleanupFailure("hide viewer IME", error); }
            viewerIme = null;
        }
        viewerMirror.close();
        if (displayInsets != null) {
            displayInsets.close();
            displayInsets = null;
        }
        releaseDisplayWakeLock();
        lastInteractiveConfirmedAtMs = 0L;
        try {
            if (display != null) display.setSurface(null);
        } catch (Throwable error) {
            logCleanupFailure("detach display surface", error);
        }
        try {
            if (display != null) display.release();
        } catch (Throwable error) {
            logCleanupFailure("release display", error);
        }
        try {
            frames.close();
        } catch (Throwable error) {
            logCleanupFailure("close frame channel", error);
        }
        try {
            if (frameThread != null) frameThread.quitSafely();
        } catch (Throwable error) {
            logCleanupFailure("stop frame thread", error);
        }
        display = null;
        reader = null;
        frameThread = null;
        displayId = -1;
        latestFrameAtMs = 0L;
        status = "STOPPED";
        return true;
    }

    @Override
    public void destroy() {
        shutdown();
        System.exit(0);
    }

    public boolean registerAsSystemService() {
        if (systemServiceRegistered) {
            return true;
        }
        try {
            Class<?> managerClass = Class.forName("android.os.ServiceManager");
            Method addService = managerClass.getDeclaredMethod("addService", String.class, IBinder.class);
            addService.setAccessible(true);
            addService.invoke(null, SERVICE_NAME, this);
            systemServiceRegistered = true;
            return true;
        } catch (Throwable t) {
            lastError = "ServiceManager.addService failed: " + t.getClass().getSimpleName() + ": " + safeMessage(t);
            return false;
        }
    }

    /**
     * Push this Binder to the app via broadcast, the way the aladdin shower server does.
     * Replaces ServiceManager.addService, which SELinux rejects for custom service names.
     */
    public boolean broadcastBinder(Context broadcastContext, String targetPackage) {
        runtimePackage = targetPackage;
        try {
            if (targetPackage == null || targetPackage.isEmpty()) {
                lastError = "broadcastBinder target package missing";
                return false;
            }
            if (packageIdentity == null || packageIdentity.state(broadcastContext) != SidecarPackageIdentity.State.CURRENT) {
                lastError = "sidecar package changed or unavailable";
                return false;
            }
            Intent intent = new Intent(SidecarBinderReceiver.ACTION_SIDECAR_BINDER_READY);
            SidecarBinderHandoff.put(intent, this, Process.myUid(), SIDECAR_PROTOCOL_VERSION,
                    packageIdentity.sourcePath, packageIdentity.updatedAt);
            intent.setPackage(targetPackage);
            broadcastContext.sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            lastError = "broadcastBinder failed: " + safeMessage(t);
            return false;
        }
    }

    private volatile IBinder platformDriver;

    @Override public IBinder getPlatformDriver() {
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException("Only the owner may use the platform driver");
        IBinder current = platformDriver;
        return current != null && current.isBinderAlive() ? current : null;
    }
    @Override public void setPlatformDriver(IBinder driver) {
        try {
            int expected = resolveContext().getPackageManager().getApplicationInfo(runtimePackage, 0).uid;
            if (android.os.Binder.getCallingUid() != expected) throw new SecurityException("Platform driver identity mismatch");
            platformDriver = driver;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public boolean requestPlatformDriver() {
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException("Only the owner may start a platform driver");
        try {
            Context broadcastContext = resolveContext();
            if (packageIdentity == null || packageIdentity.state(broadcastContext) != SidecarPackageIdentity.State.CURRENT) return false;
            Intent intent = new Intent(SidecarBinderReceiver.ACTION_SIDECAR_BINDER_READY);
            intent.setClassName(runtimePackage, "com.droidance.agent.device.RuntimeDriverBinderReceiver");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            SidecarBinderHandoff.put(intent, this, Process.myUid(), SIDECAR_PROTOCOL_VERSION,
                    packageIdentity.sourcePath, packageIdentity.updatedAt);
            broadcastContext.sendBroadcast(intent);
            return true;
        }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Override
    public IBinder startAgentRuntime(String packageName, String installationId, android.os.ParcelFileDescriptor storage) {
        try {
            if (runtimePackage == null || !runtimePackage.equals(packageName)) {
                throw new SecurityException("Runtime package does not match the paired application");
            }
            int ownerUid = resolveContext().getPackageManager().getApplicationInfo(packageName, 0).uid;
            int callerUid = android.os.Binder.getCallingUid();
            if (callerUid != ownerUid && callerUid != android.os.Process.myUid()) {
                throw new SecurityException("Runtime caller is not the paired application");
            }
            if (agentRuntimeOwner != null) {
                if (storage != null) storage.close();
                return agentRuntimeOwner;
            }
            long identity = android.os.Binder.clearCallingIdentity();
            try {
                Class<?> entry = Class.forName("com.droidance.runtime.RuntimeProcessHost");
                agentRuntimeOwner = (IBinder) entry.getMethod("start", Context.class,
                    IBinder.class, String.class, int.class, String.class, String.class, android.os.ParcelFileDescriptor.class)
                    .invoke(null, resolveContext(), asBinder(), packageName, ownerUid, installationId,
                        VirtualSpaceSidecarMain.storageDirectory(), storage);
                return agentRuntimeOwner;
            } finally { android.os.Binder.restoreCallingIdentity(identity); }
        } catch (Throwable error) {
            try { if (storage != null) storage.close(); } catch (Exception ignored) {}
            android.util.Log.e("DroidanceVSpace", "Runtime bootstrap failed", error);
            fail("runtime start failed: " + deepestCauseMessage(error));
            return null;
        }
    }

    private boolean injectTapSafely(int targetDisplayId, int x, int y, int durationMs) {
        try {
            if (!acceptsDisplay(targetDisplayId)) return false;
            injectTap(targetDisplayId, x, y, durationMs);
            noteInputEvidence("tap", INPUT_PATH_REFLECTION, true,
                    "x=" + x + ",y=" + y + ",injectedAtElapsedMs=" + SystemClock.elapsedRealtime());
            return true;
        } catch (Throwable t) {
            lastReflectionFailure = t.getClass().getSimpleName() + ": " + safeMessage(t);
            fail(lastReflectionFailure);
            return false;
        }
    }

    /** 注入证据：op + 实际通道 + 成败 + 细节，供 App 侧写进任务诊断，让通道降级不再无声。 */
    private void noteInputEvidence(String op, String path, boolean ok, String detail) {
        StringBuilder evidence = new StringBuilder()
                .append(op)
                .append(" display=").append(displayId)
                .append(" path=").append(path)
                .append(" ok=").append(ok);
        if (detail != null && !detail.isEmpty()) {
            evidence.append(" detail=").append(detail);
        }
        lastInputEvidence = evidence.toString();
        if (ok) lastError = "";
        else if (detail != null && !detail.isEmpty()) lastError = detail;
        if (INPUT_PATH_REFLECTION.equals(path) && ok) {
            lastReflectionFailure = "";
        }
    }

    private static String touchOpName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: return "touch_down";
            case MotionEvent.ACTION_MOVE: return "touch_move";
            case MotionEvent.ACTION_UP: return "touch_up";
            case MotionEvent.ACTION_CANCEL: return "touch_cancel";
            default: return "touch_" + action;
        }
    }

    private String reflectionFallbackDetail() {
        String failure = lastReflectionFailure;
        return failure.isEmpty() ? "" : "reflection_failed=" + failure;
    }

    /**
     * 让虚拟屏所在的电源组保持 interactive，这是注入能否被系统派发的**唯一前置条件**。
     *
     * OWN_DISPLAY_GROUP 让虚拟屏独占一个 power group，而这个组和主屏一样受息屏超时管辖：
     * 建屏时唤醒一次之后，没有任何"用户活动"再喂给它，几十秒后就 DOZING。真机 dumpsys 实证：
     *
     *   dumpsys power  -> Power Group 1: wakefulness=3(DOZING), mIsInteractive=false
     *   dumpsys input  -> Display not interactive: {14, 15}
     *   注入触摸        -> TouchStatesByDisplay: <no displays touched>   // 被 InputDispatcher 丢弃
     *
     * 后果是两个症状同源：agent 与人工接管一起点不动/滑不动；并且 ActivityTaskManager 不会在
     * 非 interactive 的屏上 resume 新 activity，深链 am start 返回 Success 却永远不渲染，
     * 屏上仍是上一任务最后一帧，于是"新任务里出现上一次的 App"。
     *
     * 修法（同一台真机验证通过）：以 displayId 为作用域持一把 SCREEN_BRIGHT 唤醒锁。
     *   acquire 后 dumpsys power -> wakefulness=1(AWAKE)；dumpsys input -> not interactive: {}
     *   注入触摸 -> TouchStatesByDisplay: 15 -> 目标窗口 touchingPointers=[FINGER]
     * 执行锁带超时，每个动作刷新一次，任务结束后自动过期。
     * 可见查看页另持一把随镜像生命周期释放的锁，静止查看不受执行锁超时影响。
     */
    private boolean keepDisplayInteractive(String reason) {
        if (displayId < 0) return false;
        StringBuilder evidence = new StringBuilder();
        try {
            Object powerManager = resolveContext().getSystemService(Context.POWER_SERVICE);
            if (!hasIndependentDisplayGroup()) {
                releaseDisplayWakeLock();
                Boolean interactive = displayInteractive(powerManager);
                if (interactive == null) {
                    interactive = ((android.os.PowerManager) powerManager).isInteractive();
                }
                displayWakeEvidence = "wakelock=skipped(shared-or-unknown-group), interactive=" + interactive;
                // Older platforms and some vendor displays share the physical screen's power group.
                // They may run while it is awake, but must never wake or keep that group awake.
                return interactive;
            }
            // Reuse a recent confirmation during continuous touch input while the scoped lock is held.
            long now = SystemClock.uptimeMillis();
            if (now - lastInteractiveConfirmedAtMs < DISPLAY_INTERACTIVE_REUSE_MS) return true;
            evidence.append(refreshDisplayWakeLock(powerManager) ? "wakelock=held" : "wakelock=unavailable");
            for (int attempt = 0; attempt < DISPLAY_INTERACTIVE_ATTEMPTS; attempt++) {
                Boolean interactive = displayInteractive(powerManager);
                if (interactive == null || interactive) {
                    evidence.append(", interactive=").append(interactive == null ? "unknown" : "true");
                    displayWakeEvidence = evidence.toString();
                    lastInteractiveConfirmedAtMs = SystemClock.uptimeMillis();
                    return true;
                }
                wakeDisplayGroup(powerManager);
                reportUserActivity();
                SystemClock.sleep(DISPLAY_INTERACTIVE_SETTLE_MS);
            }
            evidence.append(", interactive=false");
        } catch (Throwable t) {
            evidence.append(", error=").append(t.getClass().getSimpleName()).append(": ").append(safeMessage(t));
        }
        displayWakeEvidence = evidence.toString();
        Log.w("DroidanceVSpace", displayWakeEvidence + " reason=" + reason);
        return false;
    }

    /** 持锁/续期。锁的作用域是 displayId 所在的 power group；带超时，靠动作反复刷新。 */
    private boolean refreshDisplayWakeLock(Object powerManager) {
        try {
            if (displayWakeLock == null) {
                displayWakeLock = newDisplayWakeLock(powerManager, DISPLAY_WAKE_LOCK_TAG);
            }
            displayWakeLock.getClass().getMethod("acquire", long.class)
                    .invoke(displayWakeLock, DISPLAY_WAKE_LOCK_TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            displayWakeLock = null;
            lastReflectionFailure = "wakelock: " + t.getClass().getSimpleName() + ": " + safeMessage(t);
            return false;
        }
    }

    /** The visible mirror owns this lease, including cleanup on Binder death. */
    private AutoCloseable holdViewerDisplayAwake() throws Exception {
        if (!hasIndependentDisplayGroup()) return () -> { };
        Object lock = newDisplayWakeLock(resolveContext().getSystemService(Context.POWER_SERVICE),
                VIEWER_WAKE_LOCK_TAG);
        lock.getClass().getMethod("acquire").invoke(lock);
        return () -> lock.getClass().getMethod("release").invoke(lock);
    }

    private Object newDisplayWakeLock(Object powerManager, String tag) throws Exception {
        int targetDisplayId = displayId;
        if (targetDisplayId <= Display.DEFAULT_DISPLAY || !hasIndependentDisplayGroup()) {
            throw new IllegalStateException("Virtual display has no confirmed independent power group");
        }
        Method newWakeLock = powerManager.getClass().getMethod(
                "newWakeLock", int.class, String.class, int.class);
        Object lock = newWakeLock.invoke(powerManager,
                SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP, tag, targetDisplayId);
        if (lock == null) throw new IllegalStateException("Display wake lock unavailable");
        lock.getClass().getMethod("setReferenceCounted", boolean.class).invoke(lock, false);
        return lock;
    }

    private boolean hasIndependentDisplayGroup() {
        VirtualDisplay current = display;
        if (displayId <= Display.DEFAULT_DISPLAY || current == null) return false;
        Display target = current.getDisplay();
        if (target == null || target.getDisplayId() != displayId) return false;
        try {
            Class<?> infoClass = Class.forName("android.view.DisplayInfo");
            Object info = infoClass.getConstructor().newInstance();
            boolean available = (Boolean) Display.class.getMethod("getDisplayInfo", infoClass)
                    .invoke(target, info);
            // Display group zero owns the physical screen; -1 means the group is unknown.
            return available && infoClass.getField("displayGroupId").getInt(info) > 0;
        } catch (Throwable error) {
            lastReflectionFailure = "display power group: " + error.getClass().getSimpleName();
            return false;
        }
    }

    private Boolean displayInteractive(Object powerManager) {
        try {
            Method isInteractive = powerManager.getClass().getMethod("isInteractive", int.class);
            return (Boolean) isInteractive.invoke(powerManager, displayId);
        } catch (Throwable ignored) {
            // 拿不到按 display 的查询能力时不阻断注入：让动作照常执行，由上层的画面比对判定结果。
            return null;
        }
    }

    private void wakeDisplayGroup(Object powerManager) {
        int targetDisplayId = displayId;
        if (targetDisplayId <= Display.DEFAULT_DISPLAY) return;
        try {
            Method wakeUp = powerManager.getClass().getMethod(
                    "wakeUp", long.class, int.class, String.class, int.class);
            wakeUp.invoke(
                    powerManager,
                    SystemClock.uptimeMillis(),
                    WAKE_REASON_APPLICATION,
                    "android.server.wm:display_wakeup",
                    targetDisplayId);
        } catch (Throwable error) {
            // A device-wide wakeUp fallback wakes the user's physical screen.
            lastReflectionFailure = "display wake: " + error.getClass().getSimpleName();
        }
    }

    /** 喂一次"用户活动"，让电源组的息屏计时从现在重新开始算。 */
    private void reportUserActivity() {
        int targetDisplayId = displayId;
        if (targetDisplayId <= Display.DEFAULT_DISPLAY) return;
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, Context.POWER_SERVICE);
            if (binder == null) return;
            Class<?> stubClass = Class.forName("android.os.IPowerManager$Stub");
            Object powerService = stubClass.getDeclaredMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            Class<?> powerInterface = Class.forName("android.os.IPowerManager");
            Method userActivity = powerInterface.getMethod(
                    "userActivity", int.class, long.class, int.class, int.class);
            userActivity.invoke(powerService, targetDisplayId, SystemClock.uptimeMillis(), 0, 0);
        } catch (Throwable ignored) {
        }
    }

    private void releaseDisplayWakeLock() {
        Object lock = displayWakeLock;
        displayWakeLock = null;
        if (lock == null) return;
        try {
            lock.getClass().getMethod("release").invoke(lock);
        } catch (Throwable error) {
            logCleanupFailure("release display wake lock", error);
        }
    }

    private static void logCleanupFailure(String operation, Throwable error) {
        Log.w("DroidanceVSpace", operation + " failed: " + error.getClass().getSimpleName());
    }

    private boolean acceptsDisplay(int targetDisplayId) {
        boolean ok = displayId > 0 && targetDisplayId == displayId;
        if (!ok) {
            fail("displayId mismatch: " + targetDisplayId + " != " + displayId);
        }
        return ok;
    }

    /**
     * 注入前置：目标是虚拟屏时，先确认它 interactive。做不到就**明确失败**，不能报"点击成功"
     * ——系统丢弃触摸时注入侧看不出任何异常，无声成功会让 agent 在死屏上空转整轮预算。
     */
    private boolean acceptsInteractiveDisplay(int targetDisplayId) {
        if (!acceptsDisplay(targetDisplayId)) return false;
        if (keepDisplayInteractive("injection")) return true;
        fail("display_not_interactive: displayId=" + displayId
                + " power group is asleep, the system discards injected input (" + displayWakeEvidence + ")");
        return false;
    }

    private Context resolveContext() throws Exception {
        if (context != null) return context;
        context = SidecarShellContext.create();
        return context;
    }

    private synchronized SidecarRuntimeSurface runtimeSurface() throws Exception {
        if (runtimeSurface == null) {
            runtimeSurface = new SidecarRuntimeSurface(
                    resolveContext(),
                    this::notifyRuntimeIndicatorTapped,
                    this::notifyRuntimeStopRequested
            );
        }
        return runtimeSurface;
    }

    private void beginRuntimeInputDispatch() {
        SidecarRuntimeSurface surface = runtimeSurface;
        if (surface != null) surface.beginInputDispatch();
    }

    private void endRuntimeInputDispatch() {
        SidecarRuntimeSurface surface = runtimeSurface;
        if (surface != null) surface.endInputDispatch();
    }

    private void notifyRuntimeIndicatorTapped() {
        IRuntimeIndicatorCallback callback = runtimeIndicatorCallback;
        if (callback == null) return;
        try {
            callback.onRuntimeIndicatorTapped();
        } catch (Throwable ignored) {
            if (runtimeIndicatorCallback == callback) runtimeIndicatorCallback = null;
        }
    }

    private void notifyRuntimeStopRequested() {
        IRuntimeIndicatorCallback callback = runtimeIndicatorCallback;
        if (callback == null) return;
        try {
            callback.onRuntimeStopRequested();
        } catch (Throwable ignored) {
            if (runtimeIndicatorCallback == callback) runtimeIndicatorCallback = null;
        }
    }

    private void fail(String detail) {
        lastError = detail == null ? "" : detail;
        Log.e("DroidanceVSpace", lastError);
        if (!"READY".equals(status)) {
            status = "ERROR";
        }
    }

    private static void startIntentOnDisplay(Context context, Intent intent, int targetDisplayId) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(targetDisplayId);
        Bundle bundle = options.toBundle();
        context.startActivity(intent, bundle);
    }

    private static void injectTap(int targetDisplayId, int x, int y, int durationMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        float pressure = 0.65f + (float) (Math.random() * 0.15f);
        float size = 0.045f + (float) (Math.random() * 0.015f);
        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, pressure, size, 0, 1.0f, 1.0f, 1, 0);
        MotionEvent up = MotionEvent.obtain(
                downTime,
                downTime + Math.max(durationMs, 1),
                MotionEvent.ACTION_UP,
                x,
                y,
                0.0f,
                size,
                0,
                1.0f,
                1.0f,
                1,
                0
        );
        try {
            prepareMotionEvent(down, targetDisplayId);
            prepareMotionEvent(up, targetDisplayId);
            injectInputEvent(down);
            SystemClock.sleep(Math.max(durationMs, 1));
            injectInputEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void injectSwipe(int targetDisplayId, int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        int steps = Math.max(4, Math.min(18, durationMs / 35));
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dist = (float) Math.hypot(dx, dy);
        float normX = dist > 0.1f ? -dy / dist : 0f;
        float normY = dist > 0.1f ? dx / dist : 0f;
        float arc = Math.min(15f, dist * 0.035f);
        float ctrlX = x1 + dx * 0.5f + normX * arc;
        float ctrlY = y1 + dy * 0.5f + normY * arc;

        MotionEvent down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1, 0.55f, 0.04f, 0, 1.0f, 1.0f, 1, 0);
        try {
            prepareMotionEvent(down, targetDisplayId);
            injectInputEvent(down);
        } finally {
            down.recycle();
        }
        for (int i = 1; i < steps; i++) {
            float u = (float) i / (float) steps;
            float t = u * u * (3f - 2f * u);
            float invT = 1f - t;
            float bx = invT * invT * x1 + 2f * invT * t * ctrlX + t * t * x2;
            float by = invT * invT * y1 + 2f * invT * t * ctrlY + t * t * y2;
            int x = Math.round(bx);
            int y = Math.round(by);
            float pressure = (float) (0.55 + 0.25 * Math.sin(Math.PI * t));
            float size = (float) (0.04 + 0.015 * Math.sin(Math.PI * t));
            long eventTime = downTime + Math.round(durationMs * t);
            waitForInputTime(eventTime);
            MotionEvent move = MotionEvent.obtain(
                    downTime,
                    Math.max(eventTime, SystemClock.uptimeMillis()),
                    MotionEvent.ACTION_MOVE,
                    x,
                    y,
                    pressure,
                    size,
                    0,
                    1.0f,
                    1.0f,
                    1,
                    0
            );
            try {
                prepareMotionEvent(move, targetDisplayId);
                injectInputEvent(move);
            } finally {
                move.recycle();
            }
        }
        long upTime = downTime + Math.max(durationMs, 1);
        waitForInputTime(upTime);
        MotionEvent up = MotionEvent.obtain(
                downTime, Math.max(upTime, SystemClock.uptimeMillis()), MotionEvent.ACTION_UP, x2, y2, 0.0f, 0.04f, 0, 1.0f, 1.0f, 1, 0);
        try {
            prepareMotionEvent(up, targetDisplayId);
            injectInputEvent(up);
        } finally {
            up.recycle();
        }
    }

    /** Each event has an absolute deadline; injection overhead must not accumulate as extra sleep. */
    private static void waitForInputTime(long deadlineMs) {
        long remainingMs = deadlineMs - SystemClock.uptimeMillis();
        if (remainingMs > 0L) SystemClock.sleep(remainingMs);
    }

    private static void injectKey(int targetDisplayId, int keycode) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        int holdDuration = 25 + (int) (Math.random() * 20);
        KeyEvent down = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keycode, 0);
        KeyEvent up = new KeyEvent(downTime, downTime + holdDuration, KeyEvent.ACTION_UP, keycode, 0);
        // 必须显式声明键盘来源：source 为 UNKNOWN 的按键事件会被输入框/IME 丢弃，
        // 导致文本注入的 KEYCODE_PASTE 无声失效（粘贴不进字段）。与 injectKeyCombo 对齐。
        down.setSource(InputDevice.SOURCE_KEYBOARD);
        up.setSource(InputDevice.SOURCE_KEYBOARD);
        setInputEventDisplayId(down, targetDisplayId);
        setInputEventDisplayId(up, targetDisplayId);
        injectInputEvent(down);
        SystemClock.sleep(holdDuration);
        injectInputEvent(up);
    }

    private static void injectKeyCombo(int targetDisplayId, int[] keycodes) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        KeyComboMetaStateTracker metaState = new KeyComboMetaStateTracker();
        for (int keycode : keycodes) {
            KeyEvent down = new KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                keycode,
                0,
                metaState.onKeyDown(keycode));
            down.setSource(InputDevice.SOURCE_KEYBOARD);
            setInputEventDisplayId(down, targetDisplayId);
            injectInputEvent(down);
            SystemClock.sleep(15 + (int) (Math.random() * 15));
        }
        SystemClock.sleep(20 + (int) (Math.random() * 20));
        for (int i = keycodes.length - 1; i >= 0; i--) {
            KeyEvent up = new KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keycodes[i],
                0,
                metaState.onKeyUp(keycodes[i]));
            up.setSource(InputDevice.SOURCE_KEYBOARD);
            setInputEventDisplayId(up, targetDisplayId);
            injectInputEvent(up);
            SystemClock.sleep(15 + (int) (Math.random() * 15));
        }
    }

    private Bundle injectTextOnDisplay(int targetDisplayId, String text, boolean clearExisting, String expectedFocusTarget, String method) throws Exception {
        String target = text == null ? "" : text;
        String targetFingerprint = ClipboardTextVerifier.fingerprint(target);

        if (clearExisting && target.isEmpty()) {
            // WAIT_FOR_FINISH preserves ordering without a fixed clearing loop or sleep.
            // Selection and deletion are input delivery, not proof of the editor's value.
            injectKeyCombo(targetDisplayId, new int[] {KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A});
            if (!expectedFocusTarget.equals(getInputFocusState(targetDisplayId))) {
                Bundle evidence = emptyTextEvidence();
                evidence.putBoolean("focusConfirmed", true);
                evidence.putBoolean("payloadConfirmed", true);
                evidence.putString("detail", "selection delivered; input window changed before clearing display " + targetDisplayId);
                return evidence;
            }
            injectKey(targetDisplayId, KeyEvent.KEYCODE_DEL);
        }

        if (target.isEmpty()) {
            Bundle evidence = emptyTextEvidence();
            evidence.putBoolean("focusConfirmed", true);
            evidence.putBoolean("payloadConfirmed", true);
            evidence.putBoolean("eventInjected", true);
            evidence.putString("detail", "empty payload completed on display " + targetDisplayId);
            return evidence;
        }

        // 剪贴板回退保持原子校验：写入 → 同身份回读 → 精确比较 → 只有相等才粘贴。
        // 厂商系统可能静默拒绝后台剪贴板写入，退出码和无异常都不能代表写入成功。
        ClipboardManager clipboard = shellClipboardManager();
        if (clipboard == null) {
            Bundle evidence = emptyTextEvidence();
            evidence.putBoolean("focusConfirmed", true);
            evidence.putString("detail", "clipboard write_failed: shell ClipboardManager unavailable (" + targetFingerprint + ")");
            return evidence;
        }

        OriginalClipboard original = readOriginalClipboard(clipboard);
        if (!original.readable) {
            Bundle evidence = emptyTextEvidence();
            evidence.putBoolean("focusConfirmed", true);
            evidence.putString("detail", original.failureDetail);
            return evidence;
        }
        boolean clipboardRestoreSafe = true;
        try {
            String readback = writeAndReadBackClipboard(clipboard, target);
            boolean payloadConfirmed = ClipboardTextVerifier.matches(target, readback);
            if (!payloadConfirmed) {
                Bundle evidence = emptyTextEvidence();
                evidence.putBoolean("focusConfirmed", true);
                evidence.putString(
                        "detail",
                        "clipboard readback_mismatch: target " + targetFingerprint
                                + " readback " + ClipboardTextVerifier.fingerprint(readback)
                                + " display " + targetDisplayId);
                return evidence;
            }

            Bundle evidence = emptyTextEvidence();
            evidence.putBoolean("focusConfirmed", true);
            evidence.putBoolean("payloadConfirmed", true);
            try {
                if (clearExisting) {
                    if (!dispatchClipboardKeyCombo(targetDisplayId, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A)) {
                        evidence.putString("detail", "replacement selection unverified on display " + targetDisplayId);
                        return evidence;
                    }
                }
                // The replacement payload is one paste, avoiding partially rendered key streams.
                // Retain the verified clipboard until the editor has consumed that event.
                boolean pasteAccepted = "paste_key".equals(method)
                        ? dispatchClipboardKeyEvent(targetDisplayId, KeyEvent.KEYCODE_PASTE)
                        : dispatchClipboardKeyCombo(targetDisplayId, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V);
                clipboardRestoreSafe = !pasteAccepted;
                evidence.putBoolean("eventInjected", pasteAccepted);
                evidence.putString(
                        "detail",
                        pasteAccepted
                                ? "paste injected on display " + targetDisplayId + " [input_cmd]"
                                : "clipboard paste_failed: " + targetFingerprint + " display " + targetDisplayId);
                if (pasteAccepted && clearExisting && expectedFocusTarget.contains(";window_id=")) {
                    // `input keyevent` returning only means the event was accepted by the input
                    // dispatcher. Virtual-display editors may consume the clipboard
                    // asynchronously; prove that the focused destination consumed the target
                    // before restoring the caller's clipboard. The copy/readback is
                    // display- and app-agnostic. Inspect the same editor without replaying
                    // the paste; append operations cannot use whole-field replacement checks.
                    long readbackStarted = SystemClock.elapsedRealtime();
                    boolean destinationConsumed = confirmClipboardPasteConsumed(
                            targetDisplayId,
                            target,
                            clipboard, expectedFocusTarget, evidence);
                    evidence.putLong("readbackDurationMs", SystemClock.elapsedRealtime() - readbackStarted);
                    evidence.putBoolean("destinationTextMatched", destinationConsumed);
                    clipboardRestoreSafe = destinationConsumed;
                    if (!destinationConsumed) {
                        // The editor has not attested consumption. Restoring the clipboard now
                        // would make a queued paste read the previous clip instead of target.
                        evidence.putString("detail", "paste injected; consumption unverified; clipboard payload retained; method=" + method + " display=" + targetDisplayId);
                    }
                    if (destinationConsumed) {
                        evidence.putString(
                                "detail",
                                "paste injected on display " + targetDisplayId
                                        + " [input_cmd; destination_text_matched]");
                    }
                } else if (pasteAccepted) {
                    // Without an editor attestation, retain the paste payload rather than
                    // allowing a queued event to consume the caller's previous clipboard.
                    SystemClock.sleep(TEXT_CLIPBOARD_HANDOFF_MS);
                }
            } catch (Throwable t) {
                evidence.putString("detail", evidence.getBoolean("eventInjected", false)
                        ? "paste injected; consumption check unavailable on display " + targetDisplayId
                        : "clipboard paste_failed: " + targetFingerprint + " display " + targetDisplayId);
            }
            return evidence;
        } finally {
            if (clipboardRestoreSafe) restoreClipboardIfOwned(clipboard, target, original);
        }
    }

    private boolean confirmClipboardPasteConsumed(
            int targetDisplayId,
            String target,
            ClipboardManager clipboard, String expectedFocusTarget, Bundle evidence) {
        if (target.isEmpty()) return true;
        // Paste, selection and copy use WAIT_FOR_FINISH and retain the payload until a
        // fresh copy event. Ordered delivery needs no fixed sleep before that event.
        if (clipboard == null || !expectedFocusTarget.equals(getInputFocusState(targetDisplayId))) return false;
        CountDownLatch copiedEvent = new CountDownLatch(1);
        ClipboardManager.OnPrimaryClipChangedListener listener = copiedEvent::countDown;
        ClipData previous = readPrimaryClip(clipboard);
        if (previous == null || previous.getDescription() == null) return false;
        long previousTimestamp = previous.getDescription().getTimestamp();
        boolean copiedTextMatched = false;
        try {
            clipboard.addPrimaryClipChangedListener(listener);
            if (!dispatchClipboardKeyCombo(targetDisplayId, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A) ||
                    !dispatchClipboardKeyCombo(targetDisplayId, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C)) {
                return false;
            }
            // A fresh clipboard event distinguishes a copy from the unchanged paste payload.
            if (!copiedEvent.await(TEXT_CLIPBOARD_HANDOFF_MS, TimeUnit.MILLISECONDS)) return false;
            OriginalClipboard copied = readCurrentClipboard(clipboard);
            copiedTextMatched = copied.readable && copied.clipData != null && copied.clipData.getDescription() != null &&
                    copied.clipData.getDescription().getTimestamp() > previousTimestamp &&
                    ClipboardTextVerifier.matches(target, copied.text);
        } catch (Exception unavailable) {
            return false;
        } finally {
            try {
                clipboard.removePrimaryClipChangedListener(listener);
            } catch (Exception unavailable) {
                // Listener cleanup cannot retract an already accepted paste event.
            }
            String focusAfterCopy = getInputFocusState(targetDisplayId);
            evidence.putString("targetAfter", focusAfterCopy);
            if (expectedFocusTarget.equals(focusAfterCopy)) {
                dispatchClipboardKeyEvent(targetDisplayId, KeyEvent.KEYCODE_MOVE_END);
            }
            if (ClipboardTextVerifier.matches(target, readPrimaryClipText(clipboard))) {
                writeAndReadBackClipboard(clipboard, target);
            }
        }
        return copiedTextMatched && expectedFocusTarget.equals(evidence.getString("targetAfter"));
    }

    private static boolean dispatchClipboardKeyCombo(int targetDisplayId, int... keycodes) {
        String[] args = new String[keycodes.length + 1];
        args[0] = "keycombination";
        for (int index = 0; index < keycodes.length; index++) {
            args[index + 1] = Integer.toString(keycodes[index]);
        }
        try {
            injectKeyCombo(targetDisplayId, keycodes);
            return true;
        } catch (Throwable ignored) {
            return runTextInputCommand(targetDisplayId, args);
        }
    }

    private static boolean dispatchClipboardKeyEvent(int targetDisplayId, int keycode) {
        try {
            injectKey(targetDisplayId, keycode);
            return true;
        } catch (Throwable ignored) {
            return runTextInputCommand(targetDisplayId, "keyevent", Integer.toString(keycode));
        }
    }

    /** 原始剪贴板快照：readable=false 时 failureDetail 携带 write_failed 诊断。 */
    private static final class OriginalClipboard {
        final boolean readable;
        final String text;          // 剪贴板文本；可读且剪贴板为空时为 null
        final String failureDetail; // readable=false 时的失败证据
        final ClipData clipData;    // shell 直读时保留完整原始剪贴板，恢复用

        OriginalClipboard(boolean readable, String text, String failureDetail, ClipData clipData) {
            this.readable = readable;
            this.text = text;
            this.failureDetail = failureDetail;
            this.clipData = clipData;
        }
    }

    /**
     * 读取原始剪贴板：shell 身份直读并保留完整 ClipData。
     * readable=false 表示剪贴板不可读（写入前置检查失败，必须中止，不能盲粘）。
     */
    private static OriginalClipboard readOriginalClipboard(ClipboardManager clipboard) {
        if (clipboard == null) {
            return new OriginalClipboard(false, null, "Clipboard service unavailable", null);
        }
        try {
            if (!hasPrimaryClip(clipboard)) {
                return new OriginalClipboard(true, null, "", null);
            }
            ClipData clip = readPrimaryClip(clipboard);
            if (clip == null) {
                return new OriginalClipboard(false, null, "clipboard write_failed: original clipboard unreadable", null);
            }
            return new OriginalClipboard(true, readClipText(clip), "", clip);
        } catch (Throwable t) {
            return new OriginalClipboard(false, null, "clipboard write_failed: original clipboard unreadable", null);
        }
    }

    private static OriginalClipboard readCurrentClipboard(ClipboardManager clipboard) {
        if (clipboard == null) return new OriginalClipboard(false, null, "clipboard unavailable", null);
        try {
            ClipData clip = clipboard.getPrimaryClip();
            return clip == null
                    ? new OriginalClipboard(false, null, "clipboard unreadable", null)
                    : new OriginalClipboard(true, readClipText(clip), null, clip);
        } catch (Exception unavailable) {
            return new OriginalClipboard(false, null, "clipboard read_failed", null);
        }
    }

    private static Bundle emptyTextEvidence() {
        Bundle evidence = new Bundle();
        evidence.putBoolean("focusConfirmed", false);
        evidence.putBoolean("payloadConfirmed", false);
        evidence.putBoolean("eventInjected", false);
        evidence.putString("detail", "");
        return evidence;
    }

    /** shell 身份 ClipboardManager：SidecarShellContext.getSystemService 已把其 mContext 换成 shell 身份。 */
    private ClipboardManager shellClipboardManager() {
        try {
            return (ClipboardManager) resolveContext().getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String writeAndReadBackClipboard(ClipboardManager clipboard, String target) {
        if (clipboard == null) {
            return null;
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, target));
        } catch (Throwable ignored) {
            // 写异常时回读会拿到旧值 → matches 失败 → 触发兜底或 readback_mismatch，不会盲粘。
        }
        return ClipboardTextVerifier.awaitMatch(target, () -> readPrimaryClipText(clipboard),
                SystemClock::elapsedRealtime, SystemClock::sleep, TEXT_CLIPBOARD_HANDOFF_MS);
    }

    private static String readPrimaryClipText(ClipboardManager clipboard) {
        return readClipText(readPrimaryClip(clipboard));
    }

    private static String readClipText(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        try {
            CharSequence cs = clip.getItemAt(0).getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClipData readPrimaryClip(ClipboardManager clipboard) {
        try {
            return clipboard.getPrimaryClip();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasPrimaryClip(ClipboardManager clipboard) {
        try {
            return clipboard.hasPrimaryClip();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void restoreClipboardIfOwned(
            ClipboardManager clipboard,
            String target,
            OriginalClipboard original) {
        if (clipboard == null) {
            return;
        }
        try {
            ClipData current = readPrimaryClip(clipboard);
            CharSequence label = current == null ? null : current.getDescription().getLabel();
            String currentText = readClipText(current);
            boolean owned = label != null
                    && CLIP_LABEL.contentEquals(label)
                    && ClipboardTextVerifier.matches(target, currentText);
            if (!owned) return;
            if (original.clipData == null) {
                clipboard.clearPrimaryClip();
            } else {
                clipboard.setPrimaryClip(original.clipData);
            }
        } catch (Throwable ignored) {
            // Clipboard restoration is best-effort and must not hide the injection evidence.
        }
    }

    /**
     * 用 shell input 命令注入到指定 display（sidecar 是 shell uid，可直接 exec，WiFi 无关）。
     * 反射 injectInputEvent 是 20260722-V6/V7 真机验证过的首选路径；
     * 只有反射返回 false 或抛出时才用带 displayId 的系统 input 命令兜底。
     */
    private static boolean runInputCommand(int targetDisplayId, String... args) {
        java.lang.Process proc = null;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("/system/bin/input");
            cmd.add("-d");
            cmd.add(Integer.toString(targetDisplayId));
            for (String a : args) {
                cmd.add(a);
            }
            proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean finished = proc.waitFor(SHELL_EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return processSucceeded(proc);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable error) {
                    logCleanupFailure("destroy input process", error);
                }
            }
        }
    }

    /** Text transactions use display-scoped shell input. */
    private static boolean runTextInputCommand(int targetDisplayId, String... args) {

        return runInputCommand(targetDisplayId, args);
    }



    /**
     * `input` may print an unsupported-command diagnostic while still exiting with status 0
     * (observed for `keycombination` on Android 16). Treat that result as a failed dispatch so
     * callers can use the reflection route instead of assuming the event was delivered.
     */
    private static boolean processSucceeded(java.lang.Process proc) {
        if (proc.exitValue() != 0) return false;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            int read;
            while ((read = proc.getInputStream().read(chunk)) != -1) {
                output.write(chunk, 0, read);
            }
            String diagnostic = output.toString(StandardCharsets.UTF_8.name());
            return !diagnostic.contains("No shell command implementation.");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void prepareMotionEvent(MotionEvent event, int targetDisplayId) throws Exception {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setInputEventDisplayId(event, targetDisplayId);
    }

    private static void setInputEventDisplayId(InputEvent event, int targetDisplayId) throws Exception {
        Method method = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
        method.setAccessible(true);
        method.invoke(event, targetDisplayId);
    }

    private static void injectInputEvent(InputEvent event) throws Exception {
        injectInputEventWithRetry(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private static boolean injectInputEventOnce(InputEvent event, int mode) throws Exception {
        Class<?> inputManagerClass;
        Method getInstance;
        try {
            inputManagerClass = Class.forName("android.hardware.input.InputManager");
            getInstance = inputManagerClass.getDeclaredMethod("getInstance");
        } catch (NoSuchMethodException globalManager) {
            inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
            getInstance = inputManagerClass.getDeclaredMethod("getInstance");
        }
        Object manager = getInstance.invoke(null);
        Method inject = inputManagerClass.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        inject.setAccessible(true);
        try {
            Object result = inject.invoke(manager, event, mode);
            return !(result instanceof Boolean) || ((Boolean) result);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof SecurityException) {
                throw new SecurityException(SidecarPlatformCompat.INPUT_INJECTION_DENIED, failure.getCause());
            }
            throw failure;
        }
    }

    private static void injectInputEventWithRetry(InputEvent event, int mode) throws Exception {
        // Agent 的离散 tap/swipe 等待系统处理完整事件，避免异步 DOWN/UP 被厂商系统无声丢弃；
        // 人工接管的连续 DOWN/MOVE 保持异步，仅在 UP/CANCEL 等待队列完成。
        Exception lastError = null;
        for (int attempt = 0; attempt < INJECT_MAX_ATTEMPTS; attempt++) {
            try {
                if (injectInputEventOnce(event, mode)) return;
                lastError = new IllegalStateException("injectInputEvent returned false");
            } catch (Exception e) {
                lastError = e;
            }
            SystemClock.sleep(INJECT_RETRY_DELAY_MS);
        }
        throw lastError != null ? lastError : new IllegalStateException("injectInputEvent returned false");
    }

    private static Bitmap imageToBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            throw new IllegalStateException("image has no planes");
        }
        ByteBuffer buffer = planes[0].getBuffer().duplicate();
        buffer.rewind();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int bitmapWidth = width + Math.max(rowPadding / Math.max(pixelStride, 1), 0);
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (bitmapWidth == width) return bitmap;
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        bitmap.recycle();
        return cropped;
    }

    private static byte[] encodeImageToPng(Image image, int width, int height) throws Exception {
        Bitmap bitmap = imageToBitmap(image, width, height);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static byte[] encodeImageToJpeg(
            Image image,
            int width,
            int height,
            int maxWidth,
            int quality) throws Exception {
        Bitmap source = imageToBitmap(image, width, height);
        int targetWidth = Math.min(width, Math.max(maxWidth, 1));
        int targetHeight = Math.max(1, Math.round(height * (targetWidth / (float) width)));
        Bitmap preview = targetWidth == width
                ? source
                : Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            preview.compress(Bitmap.CompressFormat.JPEG, quality, output);
            return output.toByteArray();
        } finally {
            if (preview != source) preview.recycle();
            source.recycle();
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = null;
        try {
            Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
            activityThread = current.invoke(null);
        } catch (Throwable ignored) {
        }
        if (activityThread == null) {
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            activityThread = systemMain.invoke(null);
        }
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) getSystemContext.invoke(activityThread);
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "" : message;
    }

    private static String deepestCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}
