// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device;

import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.SurfaceControl;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/** Framework differences between Android 11 and the current platform, checked against AOSP sources. */
final class SidecarPlatformCompat {
    private static final int OWN_CONTENT_ONLY = 1 << 3;
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;

    static final String INPUT_INJECTION_DENIED = "INPUT_INJECTION_DENIED: the system blocked input from wireless "
            + "debugging. Enable the developer option for USB debugging security settings, or disable "
            + "permission monitoring, then restart wireless debugging.";

    private SidecarPlatformCompat() {}

    static boolean hasOwnDisplayFocus(int sdk) {
        return sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    static int virtualDisplayFlags(int sdk) {
        // PUBLIC lets the user's IME process obtain this display and create its native window.
        // OWN_CONTENT_ONLY prevents default-screen auto-mirroring; PRESENTATION stays disabled.
        // Releasing a public display must never move the user's background apps to display zero.
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL;
        // Shell holds ADD_TRUSTED_DISPLAY and ADD_ALWAYS_UNLOCKED_DISPLAY from Android 13 only;
        // Android 12 rejects the whole display when TRUSTED or OWN_DISPLAY_GROUP is requested.
        // 这几个 flag 是**互相依赖的一组，不要单独摘任何一个**：
        //   ALWAYS_UNLOCKED 只对"不在默认显示组"的虚拟屏合法 -> 必须与 OWN_DISPLAY_GROUP 同在，
        //     单独摘掉 OWN_DISPLAY_GROUP 会让 createVirtualDisplay 直接抛参数异常、虚拟空间连屏都建不起来；
        //   OWN_FOCUS 要求该屏是 TRUSTED。
        //   OWN_DISPLAY_GROUP 带来的独立电源组默认是 Asleep，必须由 wakeDisplayGroup() 唤醒，
        //     否则系统判该屏 not interactive 并丢弃全部注入触摸。
        if (sdk >= Build.VERSION_CODES.TIRAMISU) flags |= TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED;
        // OWN_FOCUS 是注入能否生效的前提：App 自己的查看页/接管面板长期在主屏前台持有焦点，
        // 没有这个 flag 时虚拟屏不维护自己的焦点与触摸模式，注入的 tap/swipe 会被系统丢弃
        // （agent 和人工接管同时"点不动、滑不动"）。该 flag 要求同屏已置 TRUSTED。
        // OWN_FOCUS keeps input events scoped to this display. Background text uses
        // display-scoped keys/clipboard without selecting the user's system IME.
        // A background display needs its own focus without taking the physical screen's
        // top focus and IME connection. Manual text entry is hosted by the main-screen viewer.
        if (hasOwnDisplayFocus(sdk)) flags |= OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED;
        return flags;
    }

    /** Android 14 before QPR2 reports only the buffer; later releases also pass a status code. */
    static Object newCaptureListener(Class<?> listenerClass, ObjIntConsumer<Object> callback)
            throws ReflectiveOperationException {
        try {
            return listenerClass.getConstructor(ObjIntConsumer.class).newInstance(callback);
        } catch (NoSuchMethodException bufferOnlyListener) {
            Consumer<Object> consumer = screenshot -> callback.accept(screenshot, screenshot == null ? -1 : 0);
            return listenerClass.getConstructor(Consumer.class).newInstance(consumer);
        }
    }

    static SurfaceControl.Transaction setCrop(SurfaceControl.Transaction transaction, SurfaceControl control, Rect crop) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return transaction.setCrop(control, crop);
        return invokeTransaction(transaction, "setWindowCrop",
                new Class<?>[] {SurfaceControl.class, Rect.class}, control, crop);
    }

    static SurfaceControl.Transaction setScale(SurfaceControl.Transaction transaction, SurfaceControl control, float scale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return transaction.setScale(control, scale, scale);
        return invokeTransaction(transaction, "setMatrix",
                new Class<?>[] {SurfaceControl.class, float.class, float.class, float.class, float.class},
                control, scale, 0f, 0f, scale);
    }

    private static SurfaceControl.Transaction invokeTransaction(SurfaceControl.Transaction transaction, String name,
            Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = SurfaceControl.Transaction.class.getMethod(name, parameterTypes);
            return (SurfaceControl.Transaction) method.invoke(transaction, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("SurfaceControl." + name + " unavailable", error);
        }
    }
}
