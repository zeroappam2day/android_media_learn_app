package com.example

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import kotlin.math.abs

/** Reveals an owned Queue target through its vertical list and horizontal table viewport. */
internal fun SemanticsNodeInteraction.performQaQueueScrollTo(
    nodes: SemanticsNodeInteractionsProvider
): SemanticsNodeInteraction {
    performScrollTo()
    val viewport = nodes.onNodeWithTag("queue_table_viewport")
    if (nodes.onAllNodesWithTag("queue_table_viewport").fetchSemanticsNodes().isEmpty()) return this
    repeat(3) {
        val viewportDp = viewport.getUnclippedBoundsInRoot()
        val viewportPx = viewport.fetchSemanticsNode().boundsInRoot
        check((viewportDp.right.value - viewportDp.left.value) > 0f && viewportPx.width > 0f) { "Queue viewport has no observed width" }
        val pixelsPerDp = viewportPx.width / (viewportDp.right.value - viewportDp.left.value)
        val targetDp = getUnclippedBoundsInRoot()
        val left = targetDp.left.value * pixelsPerDp
        val right = targetDp.right.value * pixelsPerDp
        val delta = when {
            right - left > viewportPx.width -> left - viewportPx.left
            left < viewportPx.left -> left - viewportPx.left
            right > viewportPx.right -> right - viewportPx.right
            else -> 0f
        }
        if (abs(delta) <= 0.5f) return this
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            check(scroll(delta, 0f)) { "Queue viewport refused observed horizontal scroll" }
        }
    }
    val viewportDp = viewport.getUnclippedBoundsInRoot()
    val targetDp = getUnclippedBoundsInRoot()
    check(targetDp.left.value >= viewportDp.left.value - 0.5f &&
        ((targetDp.right.value - targetDp.left.value) > (viewportDp.right.value - viewportDp.left.value) || targetDp.right.value <= viewportDp.right.value + 0.5f)) {
        "Owned Queue target was not revealed within the observed horizontal viewport"
    }
    return this
}
