/*
 * ConnectBot Terminal
 * Copyright 2026 Kenny Root
 * SPDX-License-Identifier: Apache-2.0
 */
package org.connectbot.terminal

import java.util.ArrayDeque

/** Consent controls decoding and display, never terminal layout. */
internal class ImageConsent {
    @Volatile var decision: Boolean? = null
        private set
    var cancelled = false
        private set
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun whenDecided(action: (Boolean) -> Unit) {
        val value = decision
        if (value == null) listeners.add(action) else action(value)
    }

    fun decide(allowed: Boolean) {
        if (decision != null) return
        decision = allowed
        val actions = listeners.toList()
        listeners.clear()
        actions.forEach { it(allowed) }
    }

    fun cancel() {
        cancelled = true
        decide(false)
    }
}

/** Serializes bounded consent prompts while the ordinary protocol parser keeps running. */
internal class InlineImageConsentGate(
    private val limit: Int,
    private val ask: (InlineImageRequest, (Boolean) -> Unit) -> Unit,
) {
    private data class Prompt(val request: InlineImageRequest, val consent: ImageConsent)
    private val prompts = ArrayDeque<Prompt>()
    private var active: Prompt? = null

    fun request(request: InlineImageRequest): ImageConsent {
        require(prompts.size + (if (active == null) 0 else 1) < limit) { "ENOSPC:too many pending image requests" }
        val consent = ImageConsent()
        prompts.add(Prompt(request, consent))
        startNextPrompt()
        return consent
    }

    fun reset() {
        val pending = prompts.toList() + listOfNotNull(active)
        prompts.clear()
        active = null
        pending.forEach { it.consent.cancel() }
    }

    private fun startNextPrompt() {
        if (active != null || prompts.isEmpty()) return
        val prompt = prompts.removeFirst()
        active = prompt
        ask(prompt.request) { allowed ->
            if (active !== prompt) return@ask
            active = null
            prompt.consent.decide(allowed)
            startNextPrompt()
        }
    }
}
