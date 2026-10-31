package com.example.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppActionHint
import kotlinx.coroutines.launch

/** One column contract for discovered files and backlog; file identity remains pinned. */
object FileColumns {
    val location = 160.dp
    val size = 64.dp
    val status = 80.dp
    val actions = 240.dp
}

@Composable
fun FileTableHeader(fileWidth: Dp, scroll: ScrollState, tag: String) {
    val scope = rememberCoroutineScope()
    val page = with(LocalDensity.current) { FileColumns.location.roundToPx() }
    val scrollLabel = if (scroll.canScrollForward) "Next columns" else "First columns"
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).testTag("${tag}_columns"),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(fileWidth).heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("File", Modifier.weight(1f).testTag("${tag}_header_file").semantics { heading() },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            AppActionHint(scrollLabel) {
                IconButton(onClick = { scope.launch {
                    scroll.animateScrollTo(if (scroll.canScrollForward) (scroll.value + page).coerceAtMost(scroll.maxValue) else 0)
                } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = scrollLabel, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(scroll)) {
            HeaderCell("Location", Modifier.width(FileColumns.location), "${tag}_header_location")
            HeaderCell("Size", Modifier.width(FileColumns.size), "${tag}_header_size", true)
            HeaderCell("Status", Modifier.width(FileColumns.status), "${tag}_header_status", true)
            HeaderCell("Actions", Modifier.width(FileColumns.actions), "${tag}_header_actions", true)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier, tag: String, centered: Boolean = false) {
    Box(modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp).testTag(tag).semantics { heading() },
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Slots retain existing selection, evidence and action handlers; paint never changes model state. */
@Composable
fun FileTableRow(
    fileWidth: Dp,
    scroll: ScrollState,
    name: String,
    location: String,
    size: String,
    status: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: @Composable () -> Unit = {},
    evidence: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit
) {
    Column(modifier.fillMaxWidth().background(background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(fileWidth).padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                leading()
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                    evidence()
                }
            }
            Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(FileColumns.location).padding(8.dp)) {
                    Text(location, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.width(FileColumns.size).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(size, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Box(Modifier.width(FileColumns.status).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(status, fontSize = 11.sp, lineHeight = 15.sp, color = statusColor)
                }
                Row(Modifier.width(FileColumns.actions), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center, content = actions)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}
