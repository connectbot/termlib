package org.connectbot.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class SearchManager {
    var query by mutableStateOf("")
        private set

    var matches by mutableStateOf<List<SearchMatch>>(emptyList())
        private set

    var activeMatchIndex by mutableIntStateOf(-1)
        private set

    val matchCount: Int
        get() = matches.size

    fun setMatches(
        query: String,
        matches: List<SearchMatch>,
    ) {
        this.query = query
        this.matches = matches
        activeMatchIndex = if (matches.isNotEmpty()) 0 else -1
    }

    fun next(): SearchMatch? {
        if (matches.isEmpty()) {
            return null
        }

        activeMatchIndex = if (activeMatchIndex >= matches.lastIndex) {
            0
        } else {
            activeMatchIndex + 1
        }

        return matches[activeMatchIndex]
    }

    fun previous(): SearchMatch? {
        if (matches.isEmpty()) {
            return null
        }

        activeMatchIndex = if (activeMatchIndex <= 0) {
            matches.lastIndex
        } else {
            activeMatchIndex - 1
        }

        return matches[activeMatchIndex]
    }

    fun activeMatch(): SearchMatch? {
        return matches.getOrNull(activeMatchIndex)
    }

    fun clear() {
        query = ""
        matches = emptyList()
        activeMatchIndex = -1
    }
}
