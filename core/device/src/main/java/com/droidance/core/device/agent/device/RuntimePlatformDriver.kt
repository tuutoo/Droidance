// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

/** Authenticated platform Binder handoff never waits for the GUI's application graph. */
object RuntimePlatformDriver {
    const val BIND_TIMEOUT = "PLATFORM_DRIVER_BIND_TIMEOUT"
    const val BIND_TIMEOUT_MS = 5_000L

    internal class BindTimeoutException : IllegalStateException(BIND_TIMEOUT)

    fun connect(): INativeInputDriver = connect(
        IVirtualSpaceSidecar.Stub.asInterface(requireNotNull(SidecarBinderRegistry.get(2000)))
    )

    @Synchronized fun connect(sidecar: IVirtualSpaceSidecar): INativeInputDriver {
        check(RuntimeProcessRole.isOwner)
        fun availableDriver(): INativeInputDriver? = sidecar.platformDriver
            ?.takeIf { it.isBinderAlive && it.pingBinder() }
            ?.let(INativeInputDriver.Stub::asInterface)
        availableDriver()?.let { return it }
        check(sidecar.requestPlatformDriver())
        val deadline = android.os.SystemClock.uptimeMillis() + BIND_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            availableDriver()?.let { return it }
            android.os.SystemClock.sleep(20)
        }
        throw BindTimeoutException()
    }
}
