package com.example.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Native presentation rules shared by feature controls, including future screens. */
object Presentation {
    val target = 48.dp
    val icon = 22.dp
    val gap = 8.dp
    val inset = 12.dp
    val railInset = 40.dp
    const val transitionMillis = 220
}

/** Observes pointer state without consuming input or replacing the child's click behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionHint(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.91f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 650f),
        label = "action feedback"
    )
    Box(modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    pressed = false
                }
            }
        }) { content() }
    }
    }
}

@Composable
fun AppIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String = label,
    selected: Boolean? = null
) {
    AppActionHint(hint) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(Presentation.target).semantics { if (selected != null) this.selected = selected }) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(Presentation.icon),
                    tint = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else if (selected == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                if (selected == true) Box(Modifier.align(Alignment.BottomCenter).padding(top = 24.dp)
                    .size(3.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    }
}

/** A restrained surface gradient gives the shared workspace depth without moving data. */
@Composable
fun canvasBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return remember(colors) {
        Brush.verticalGradient(listOf(colors.surface, colors.background, colors.surfaceVariant))
    }
}
