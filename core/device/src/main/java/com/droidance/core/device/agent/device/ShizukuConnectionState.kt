// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device

/** Authorization and service availability are independent of the resident device Binder. */
data class ShizukuConnectionState(
    val installed: Boolean = false,
    val running: Boolean = false,
    val supportedVersion: Boolean = true,
    val shellIdentity: Boolean = false,
    val authorized: Boolean = false,
    val permissionBlocked: Boolean = false
) {
    val ready: Boolean get() = running && supportedVersion && shellIdentity && authorized
}
