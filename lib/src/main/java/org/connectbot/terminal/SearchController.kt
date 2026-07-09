package org.connectbot.terminal

interface SearchController {
    val query: String
    val matchCount: Int
    val activeMatchIndex: Int

    fun find(query: String): Int

    fun next(): Boolean

    fun previous(): Boolean

    fun clear()
}
