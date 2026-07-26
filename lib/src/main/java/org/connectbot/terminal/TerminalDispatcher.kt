/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** FIFO submission never waits for terminal state. Work is serialized per session. */
internal class TerminalDispatcher {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val scheduled = AtomicBoolean()
    private val executing = ThreadLocal<Boolean>()

    fun execute(action: () -> Unit) {
        queue.add(Runnable(action))
        schedule()
    }

    fun executeAfter(delayMillis: Long, action: () -> Unit) {
        workers.schedule({ execute(action) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    fun <T> call(action: () -> T): T {
        if (executing.get() == true) return action()
        val task = FutureTask(action)
        execute { task.run() }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        workers.execute {
            executing.set(true)
            try {
                // Yield between batches so one busy terminal cannot own the pool.
                for (index in 0 until 64) {
                    val task = queue.poll() ?: break
                    task.run()
                }
            } finally {
                executing.remove()
                scheduled.set(false)
                if (queue.isNotEmpty()) schedule()
            }
        }
    }

    companion object {
        private val workers = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "terminal-state").apply { isDaemon = true }
        }
    }
}

/** Used only by Compose. The public emulator retains synchronous behavior. */
internal class QueuedTerminal(private val terminal: TerminalEmulatorImpl) : TerminalEmulator by terminal {
    fun text(modifiers: Int, value: String, normalizeNewlines: Boolean) {
        terminal.commands.execute { terminal.batchOutput { dispatchText(terminal, modifiers, value, normalizeNewlines) } }
    }
    override fun dispatchKey(modifiers: Int, key: Int) {
        terminal.commands.execute { terminal.dispatchKey(modifiers, key) }
    }
    override fun dispatchCharacter(modifiers: Int, codepoint: Int) {
        terminal.commands.execute { terminal.dispatchCharacter(modifiers, codepoint) }
    }
}

internal fun dispatchText(terminal: TerminalEmulator, modifiers: Int, text: String, normalizeNewlines: Boolean) {
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        if (cp == 10 || (normalizeNewlines && cp == 13)) {
            terminal.dispatchKey(modifiers, VTermKey.ENTER)
            if (normalizeNewlines && cp == 13 && index + 1 < text.length && text[index + 1] == '\n') index++
        } else {
            terminal.dispatchCharacter(modifiers, cp)
        }
        index += Character.charCount(cp)
    }
}
