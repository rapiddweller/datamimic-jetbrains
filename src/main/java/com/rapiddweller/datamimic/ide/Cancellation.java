// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide;

import com.intellij.platform.ide.progress.TaskCancellation;

/** Kotlin resolves this through a Companion absent from 2024.2; the Java static method exists there. */
final class Cancellation {
    private Cancellation() {
    }

    static TaskCancellation cancellable() {
        return TaskCancellation.cancellable();
    }
}
