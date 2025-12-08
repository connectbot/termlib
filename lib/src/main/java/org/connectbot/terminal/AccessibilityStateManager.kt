/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Composable that provides accessibility state as a State object.
 * Automatically handles cleanup when the composable leaves the composition.
 *
 * This tracks whether accessibility services (like TalkBack) are enabled,
 * allowing the app to conditionally enable accessibility features only when needed
 * to avoid performance penalties.
 *
 * @return State<Boolean> that tracks whether accessibility is enabled
 */
@Composable
internal fun rememberAccessibilityState(): State<Boolean> {
    val context = LocalContext.current

    val accessibilityManager = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    val accessibilityState = remember {
        mutableStateOf(accessibilityManager.isEnabled)
    }

    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
            accessibilityState.value = enabled
        }

        accessibilityManager.addAccessibilityStateChangeListener(listener)

        onDispose {
            accessibilityManager.removeAccessibilityStateChangeListener(listener)
        }
    }

    return accessibilityState
}
