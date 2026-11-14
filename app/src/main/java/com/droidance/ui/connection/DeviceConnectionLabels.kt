// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.ui.connection

import androidx.annotation.StringRes
import com.droidance.R
import com.droidance.core.device.agent.device.DeviceConnectionMethod

@StringRes
internal fun connectionMethodLabel(method: DeviceConnectionMethod): Int = when (method) {
    DeviceConnectionMethod.WIRELESS -> R.string.connection_method_wireless
    DeviceConnectionMethod.ROOT -> R.string.connection_method_root
    DeviceConnectionMethod.SHIZUKU -> R.string.connection_method_shizuku
}
