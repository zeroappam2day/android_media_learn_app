package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class UxEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        OffCanvasPanelLayout(
                            expanded = expanded,
                            modifier = Modifier.fillMaxSize(),
                            panel = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(top = 80.dp, start = 20.dp, end = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text("Menu", fontWeight = FontWeight.Bold)
                                    MediaManagementNavigation("sources") {}
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "Synthetic summary",
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                            },
                            content = {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(top = 96.dp, start = 20.dp, end = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    Text("Sources", fontWeight = FontWeight.Bold)
                                    Text("Synthetic state: Sources, 7, 23")
                                    Card(modifier = Modifier.fillMaxWidth().testTag("fixture_guide")) {
                                        Text("Guide", modifier = Modifier.padding(16.dp))
                                    }
                                    val iconActions = listOf(
                                        "Home" to Icons.Default.Home,
                                        "Back" to Icons.Default.ArrowBack,
                                        "Close" to Icons.Default.Close,
                                        "Menu" to Icons.Default.Menu,
                                        "Search" to Icons.Default.Search,
                                        "Sort" to Icons.Default.Sort,
                                        "Filter" to Icons.Default.FilterAlt,
                                        "Scan" to Icons.Default.YoutubeSearchedFor,
                                        "Refresh" to Icons.Default.Refresh,
                                        "Add" to Icons.Default.Add,
                                        "Edit" to Icons.Default.Edit,
                                        "Save" to Icons.Default.Save,
                                        "Delete" to Icons.Default.Delete,
                                        "Archive" to Icons.Default.Archive,
                                        "Restore" to Icons.Default.Restore,
                                        "Keep" to Icons.Default.Check,
                                        "Remove" to Icons.Default.RemoveCircle,
                                        "Play" to Icons.Default.PlayArrow,
                                        "Pause" to Icons.Default.Pause,
                                        "Next" to Icons.Default.SkipNext,
                                        "Settings" to Icons.Default.Settings,
                                        "Theme" to Icons.Default.LightMode,
                                        "Computer" to Icons.Default.Computer,
                                        "Expand" to Icons.Default.ExpandMore,
                                        "Collapse" to Icons.Default.ExpandLess
                                    )
                                    Column {
                                        iconActions.chunked(5).forEach { actionRow ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceEvenly
                                            ) {
                                                actionRow.forEach { (description, icon) ->
                                                    IconButton(
                                                        onClick = {},
                                                        modifier = Modifier.size(40.dp)
                                                    ) {
                                                        Icon(icon, contentDescription = description)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 32.dp, end = 12.dp)
                        ) {
                            Icon(
                                if (expanded) Icons.Default.Close else Icons.Default.Menu,
                                contentDescription = if (expanded) "Close menu" else "Open menu"
                            )
                        }
                    }
                }
            }
        }
    }
}
