/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import android.os.Build
import android.os.Trace

/** Diagnostic counters only; no API or frame-clock ownership. */
internal object TerminalFrameTrace {
    fun draw(snapshot: TerminalSnapshot) {
        if (Build.VERSION.SDK_INT < 29 || !Trace.isEnabled()) return
        Trace.setCounter("terminal.draw.sequence", snapshot.sequenceNumber)
        val cells = snapshot.lines.firstOrNull()?.cells ?: return
        if (cells.size < 8) return
        var tick = 0L
        for (col in 0..7) {
            val ch = cells.charAt(col)
            if (ch !in '0'..'9') return
            tick = tick * 10 + ch.code - '0'.code
        }
        Trace.setCounter("terminal.draw.tick", tick)
    }
}
