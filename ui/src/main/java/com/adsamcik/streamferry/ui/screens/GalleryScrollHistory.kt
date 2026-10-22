package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.saveable.listSaver

/** Small saveable LRU of anchors, shared by grid and season layouts across folder/search navigation. */
internal class GalleryScrollHistory {
    class LayoutStates(val grid: LazyGridState, val seasons: LazyListState)
    private val contexts = LinkedHashMap<String, LayoutStates>()

    fun statesFor(key: String): LayoutStates {
        val states = contexts.remove(key) ?: LayoutStates(LazyGridState(), LazyListState())
        contexts[key] = states
        while (contexts.size > 32) contexts.remove(contexts.keys.first())
        return states
    }

    companion object {
        val Saver = listSaver<GalleryScrollHistory, Any>(
            save = { history -> history.contexts.flatMap { (key, states) ->
                listOf(key, states.grid.firstVisibleItemIndex, states.grid.firstVisibleItemScrollOffset,
                    states.seasons.firstVisibleItemIndex, states.seasons.firstVisibleItemScrollOffset)
            } },
            restore = { values -> GalleryScrollHistory().apply {
                values.chunked(5).takeLast(32).forEach { entry ->
                    contexts[entry[0] as String] = LayoutStates(
                        LazyGridState(entry[1] as Int, entry[2] as Int),
                        LazyListState(entry[3] as Int, entry[4] as Int),
                    )
                }
            } },
        )
    }
}
