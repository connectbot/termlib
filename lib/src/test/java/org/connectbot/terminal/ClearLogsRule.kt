/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import com.github.valfirst.slf4jtest.TestLogger
import org.junit.rules.ExternalResource

/** Clears captured logs from all threads, including image decoder workers. */
class ClearLogsRule(private val logger: TestLogger) : ExternalResource() {
    override fun before() {
        logger.clearAll()
    }

    override fun after() {
        logger.clearAll()
    }
}
