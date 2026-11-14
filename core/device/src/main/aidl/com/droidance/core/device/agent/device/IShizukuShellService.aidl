// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.device.agent.device;
import android.os.Bundle;

interface IShizukuShellService {
    void destroy() = 16777114;
    int getUid() = 1;
    Bundle execute(String command, long timeoutMs, String executionId) = 2;
}
