@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.VideoItem

/** Reference-inspired depth and finite choreography; every displayed value comes from the library. */
@Composable
fun LibraryPresentation(
    media: List<VideoItem>,
    onSources: () -> Unit,
    onVideos: () -> Unit,
    onImages: () -> Unit,
    motionEnabled: Boolean = Settings.Global.getFloat(
        LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) > 0f
) {
    val available = remember(media) { media.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed } }
    val images = available.count { it.mimeType?.startsWith("image/") == true }
    val videos = available.count { it.mimeType?.startsWith("video/") == true }
    val saved = available.count { it.status == "processed" || it.status == "archived" }
    val reveal = remember { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled) reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        else reveal.snapTo(1f)
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("library_presentation"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = colors.surface,
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 24.dp.toPx()
            }
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Your library,", fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Normal)
                Text("at a glance.", fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f
                    val figures: @Composable () -> Unit = {
                        Column {
                            Text(available.size.toString(), fontSize = 64.sp, lineHeight = 70.sp,
                                fontWeight = FontWeight.Light, letterSpacing = (-3).sp,
                                modifier = Modifier.testTag("library_total"))
                            Text("available files", color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                    if (stacked) {
                        Column(Modifier.fillMaxWidth()) {
                            figures()
                            MediaSculpture(reveal.value, Modifier.align(Alignment.CenterHorizontally))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { figures() }
                            MediaSculpture(reveal.value)
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val progress = if (available.isEmpty()) 0f else saved.toFloat() / available.size
                    Canvas(Modifier.size(40.dp).semantics {
                        contentDescription = "$saved of ${available.size} available files saved"
                    }.testTag("library_completion")) {
                        val inset = 4.dp.toPx()
                        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                        drawArc(colors.outlineVariant, -90f, 360f, false, Offset(inset, inset), arcSize,
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                        drawArc(colors.primary, -90f, 360f * progress * reveal.value, false, Offset(inset, inset), arcSize,
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("$saved saved", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("From your available media", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().graphicsLayer {
            val fraction = ((reveal.value - 0.2f) / 0.8f).coerceIn(0f, 1f)
            alpha = fraction
            translationY = (1f - fraction) * 20.dp.toPx()
        }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
            LibraryShortcut("Images", images, Icons.Default.Image, onImages, Modifier.weight(1f))
            LibraryShortcut("Videos", videos, Icons.Default.VideoLibrary, onVideos, Modifier.weight(1f))
        }
        AppActionHint("Review media sources") {
            FilledTonalButton(onClick = onSources, shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = Presentation.target).testTag("library_review")) {
                Column(Modifier.weight(1f)) {
                    Text("Review your media", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Choose what to keep", fontSize = 11.sp)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

/** A media-specific dimensional illustration, not a chart or a simulated processing state. */
@Composable
private fun MediaSculpture(reveal: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(152.dp, 174.dp).testTag("media_sculpture"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawOval(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                center = center, radius = size.width * 0.6f), topLeft = Offset(0f, size.height * 0.68f),
                size = Size(size.width, size.height * 0.3f))
        }
        Surface(shape = RoundedCornerShape(22.dp), color = colors.secondaryContainer,
            shadowElevation = 5.dp,
            modifier = Modifier.size(108.dp, 128.dp).graphicsLayer {
                rotationZ = -15f * reveal
                translationX = -15.dp.toPx() * reveal
                translationY = -7.dp.toPx() * reveal
            }) {
            Box(Modifier.background(Brush.linearGradient(listOf(colors.surface, colors.secondaryContainer))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Image, contentDescription = null, tint = colors.onSecondaryContainer.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
            }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = colors.primary,
            shadowElevation = 12.dp,
            modifier = Modifier.size(104.dp, 126.dp).graphicsLayer {
                rotationZ = 12f * reveal
                translationX = 13.dp.toPx() * reveal
                translationY = 9.dp.toPx() * reveal
                scaleX = 0.82f + 0.18f * reveal
                scaleY = scaleX
            }) {
            Box(Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.primary))), contentAlignment = Alignment.Center) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
                }
                Box(Modifier.align(Alignment.BottomStart).padding(12.dp).size(26.dp, 3.dp).background(colors.onPrimary.copy(alpha = 0.65f), CircleShape))
            }
        }
    }
}

@Composable
private fun LibraryShortcut(label: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    AppActionHint("Open saved ${label.lowercase()}", modifier) {
        OutlinedCard(onClick = onClick, shape = MaterialTheme.shapes.large,
            colors = CardDefaults.outlinedCardColors(containerColor = colors.surface.copy(alpha = 0.85f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
            modifier = Modifier.fillMaxWidth().testTag("library_${label.lowercase()}")) {
            Column(Modifier.padding(16.dp)) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(10.dp))
                Text(count.toString(), fontSize = 28.sp, fontWeight = FontWeight.Light)
                Text("$label available", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}
