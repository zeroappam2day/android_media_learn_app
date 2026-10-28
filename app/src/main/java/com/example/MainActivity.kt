@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.net.URL
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.*
import com.example.ui.VideoViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.AppActionHint as ActionHint
import com.example.ui.theme.AppIconAction
import com.example.ui.theme.Presentation
import com.example.ui.theme.canvasBrush
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

enum class WindowState { STANDARD, MINIMIZED, MAXIMIZED }

val defaultSystemPrompt = "You are a Samsung S23 Ultra automation expert. Extract commands and roadmap."

@Composable
fun WindowedModal(
    title: String,
    windowState: WindowState,
    onStateChange: (WindowState?) -> Unit,
    secure: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    if (windowState == WindowState.MINIMIZED) return

    val isMaximized = windowState == WindowState.MAXIMIZED
    val modifier = if (isMaximized) {
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    } else {
        Modifier
            .fillMaxWidth(0.9f)
            .fillMaxHeight(0.8f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
    }

    Dialog(
        onDismissRequest = { onStateChange(null) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit
        )
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = modifier,
            shape = if (isMaximized) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top window bar with custom window actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Minimize Button
                        ActionHint("Minimize") { IconButton(
                            onClick = { onStateChange(WindowState.MINIMIZED) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Minimize",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } }
                        
                        // Full Window or Return Window Button
                        ActionHint(if (isMaximized) "Restore window size" else "Expand window") { IconButton(
                            onClick = { 
                                if (isMaximized) {
                                    onStateChange(WindowState.STANDARD)
                                } else {
                                    onStateChange(WindowState.MAXIMIZED)
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isMaximized) "Standard Window" else "Full Window",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } }

                        // Close Button
                        ActionHint("Close") { IconButton(
                            onClick = { onStateChange(null) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } }
                    }
                }
                
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    content()
                }
            }
        }
    }
}

}

@Composable
internal fun OffCanvasPanelLayout(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    panel: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val contentOffset by animateDpAsState(
            targetValue = if (expanded) maxWidth else 0.dp,
            animationSpec = tween(durationMillis = 240),
            label = "panelOffset"
        )
        AnimatedVisibility(
            visible = expanded,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier.fillMaxSize().testTag("nav_panel")
        ) {
            Box(modifier = Modifier.fillMaxSize(), content = panel)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = contentOffset)
                .testTag("offcanvas_content"),
            content = content
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var isDarkOverride by remember { mutableStateOf<Boolean?>(null) }
            val isDark = isDarkOverride ?: systemDark

            MyApplicationTheme(darkTheme = isDark) {
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isDark = isDark,
                        onToggleTheme = { isDarkOverride = !isDark }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: VideoViewModel = viewModel(),
    isDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val allVideos by viewModel.allVideos.collectAsState()
    val filteredVideos by viewModel.filteredVideos.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val archiveSort by viewModel.selectedSort.collectAsState()
    val mediaAccess by viewModel.mediaAccessState.collectAsState()
    val ollamaState by viewModel.ollamaStatus.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val lastScan by viewModel.lastScanResult.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val llmTemperature by viewModel.llmTemperature.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val connectionSuccess by viewModel.connectionSuccess.collectAsState()

    val selectedVideo by viewModel.selectedVideo.collectAsState()
    val selectedFrames by viewModel.selectedVideoFrames.collectAsState()
    val selectedAnalysis by viewModel.selectedVideoAnalysis.collectAsState()
    val allAnalysisResults by viewModel.allAnalysisResults.collectAsState()
    val inventoryTotals by viewModel.inventoryTotals.collectAsState()
    val sourceAggregates by viewModel.sourceAggregates.collectAsState()
    val selectedSourceCategory by viewModel.selectedSourceCategory.collectAsState()
    val selectedSourceSubcategories by viewModel.selectedSourceSubcategories.collectAsState()

    val batchState by viewModel.processor.processorState.collectAsState()
    val activeVideoId by viewModel.processor.activeVideoId.collectAsState()
    val currentProgress by viewModel.processor.currentVideoProgress.collectAsState()
    val currentStep by viewModel.processor.currentStepName.collectAsState()

    val autoIngestEnabled by viewModel.autoIngestEnabled.collectAsState()
    val autoIngestScreenshots by viewModel.autoIngestScreenshots.collectAsState()
    val autoIngestRecordings by viewModel.autoIngestRecordings.collectAsState()
    val mediaRules by viewModel.mediaRules.collectAsState()
    val customTags by viewModel.customTags.collectAsState()
    val tagAssignments by viewModel.tagAssignments.collectAsState()
    val binMedia by viewModel.binMedia.collectAsState()
    val ruleMessage by viewModel.ruleMessage.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var dismissLimitedBanner by remember { mutableStateOf(false) }
    var showEngineGuide by remember { mutableStateOf(false) }

    var pendingStorageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingStorageTrash by rememberSaveable { mutableStateOf(false) }
    // Intent Sender Launcher for Trashing & Deleting
    val systemActionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val id = pendingStorageId
        pendingStorageId = null
        if (result.resultCode == Activity.RESULT_OK && id != null) {
            viewModel.reconcileStorageAction(id, pendingStorageTrash)
        }
    }
    fun requestStorageAction(id: Long, trash: Boolean) {
        try {
            val pending = if (trash) viewModel.createTrashPendingIntent(id) else viewModel.createDeletePendingIntent(id)
            if (pending == null) {
                Toast.makeText(context, "This storage action requires Android 11 or later.", Toast.LENGTH_LONG).show()
                return
            }
            pendingStorageId = id
            pendingStorageTrash = trash
            systemActionLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        } catch (_: Exception) {
            pendingStorageId = null
            Toast.makeText(context, "Android could not open the storage confirmation. Scan and try again.", Toast.LENGTH_LONG).show()
        }
    }
    val renameAuthorization by viewModel.renameAuthorization.collectAsState()
    val renameLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.finishRename(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(renameAuthorization) {
        if (renameAuthorization != null) {
            viewModel.consumeRenameAuthorization()?.let {
                try {
                    renameLauncher.launch(IntentSenderRequest.Builder(it).build())
                } catch (_: Exception) {
                    viewModel.finishRename(false)
                    Toast.makeText(context, "Android could not open the rename confirmation. Try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val operationMessage by viewModel.operationMessage.collectAsState()
    LaunchedEffect(operationMessage) {
        operationMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearOperationMessage()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportMetadata(it) { success ->
            Toast.makeText(context, if (success) "Media metadata exported." else "Export failed. Choose a writable destination and retry.", Toast.LENGTH_LONG).show()
        } }
    }

    val binDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.finishBinDelete(result.resultCode == Activity.RESULT_OK) }

    // Storage Permission Launchers
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.refreshMediaAccess()
        val readVideo = permissions[Manifest.permission.READ_MEDIA_VIDEO] ?: false
        val readImages = permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        val partialRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] ?: false
        } else false

        if (readVideo || readImages || partialRead) {
            viewModel.scanVideos()
        } else {
            viewModel.scanVideos()
            Toast.makeText(context, "Permissions denied. App needs photo or video access to build the inventory.", Toast.LENGTH_LONG).show()
        }
    }

    fun triggerPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            )
        }
    }


    Scaffold(
        modifier = Modifier.fillMaxSize().background(canvasBrush()),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            val appBarTitle: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f).testTag("app_title")
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        
                    }
            }
            val appBarActions: @Composable RowScope.() -> Unit = {
                    ActionHint("Save file details") { IconButton(
                        onClick = { exportLauncher.launch("media-metadata.json") },
                        modifier = Modifier.testTag("computer_transfer_button")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Save file details")
                    } }
                    ActionHint("Toggle Theme") { IconButton(onClick = onToggleTheme) {
                        val icon = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode
                        Icon(imageVector = icon, contentDescription = "Toggle Theme")
                    } }
                    ActionHint("Settings") { IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    } }
            }
            BoxWithConstraints {
                val compactHeader = maxWidth < 600.dp || LocalDensity.current.fontScale > 1.2f
                if (compactHeader) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets).padding(horizontal = 16.dp, vertical = 8.dp)) {
                            appBarTitle()
                            Row(Modifier.align(Alignment.End), content = appBarActions)
                        }
                    }
                } else {
                    TopAppBar(
                        title = appBarTitle,
                        actions = appBarActions,
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        // Modals Window States
        var leftPanelExpanded by rememberSaveable { mutableStateOf(true) }
        var activePanelSection by remember { mutableStateOf("home") } // "home", "sources", "folders", "backlog", "archive", "settings"
        var sourceTool by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(enabled = sourceTool != null) { sourceTool = null }
        var folderSearchQuery by remember { mutableStateOf("") }
        
        var headerPanelExpanded by remember { mutableStateOf(true) }

        // Custom Stateful Modals
        var previewModalState by remember { mutableStateOf<WindowState?>(null) }
        var previewModalVideoId by remember { mutableStateOf<Long?>(null) }
        val previewDrafts = rememberSaveableStateHolder()

        var outputModalState by remember { mutableStateOf<WindowState?>(null) }
        var outputModalVideoId by remember { mutableStateOf<Long?>(null) }

        var settingsModalState by remember { mutableStateOf<WindowState?>(null) }
        val settingsDrafts = rememberSaveableStateHolder()
        var settingsApiKeyDraft by remember { mutableStateOf("") }

        // Folder selection states
        val selectedFolders = remember { mutableStateMapOf<String, Boolean>() }
        
        // Archive selection states
        // Archive selection states
        val selectedArchiveVideos = remember(activePanelSection) { mutableStateMapOf<Long, Boolean>() }

        if (showSettings) {
            settingsDrafts.removeState("settings")
            settingsApiKeyDraft = ""
            settingsModalState = WindowState.STANDARD
            showSettings = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Production and evidence tests share this full-width off-canvas primitive.
                OffCanvasPanelLayout(
                    expanded = leftPanelExpanded,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    panel = {
                        Column(
                            modifier = Modifier
                                .width(240.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                                .padding(12.dp)
                        ) {
                            Text("MENU", fontSize = 11.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                            
                            // Navigation items list
                            MediaManagementNavigation(activePanelSection) { destination ->
                                activePanelSection = destination
                                sourceTool = null
                                leftPanelExpanded = false
                            }
                            Divider(modifier = Modifier.padding(vertical = 12.dp))

                            // Dynamic Folder Multi-Selector
                            if (activePanelSection == "folders") {
                                Text("Folders", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                                
                                OutlinedTextField(
                                    value = folderSearchQuery,
                                    onValueChange = { folderSearchQuery = it },
                                    placeholder = { Text("Search folders...", fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                    trailingIcon = {
                                        if (folderSearchQuery.isNotEmpty()) {
                                            ActionHint("Clear search") { IconButton(onClick = { folderSearchQuery = "" }, modifier = Modifier.size(16.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                                            } }
                                        } else {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    )
                                )

                                // Extract and group folder strings from available videos
                                val folders = remember(allVideos) {
                                    allVideos.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.map { mediaFolderLabel(it) }.distinct().filter { it.isNotEmpty() }
                                }

                                val filteredFolders = remember(folders, folderSearchQuery) {
                                    folders.filter { it.contains(folderSearchQuery, ignoreCase = true) }
                                }

                                if (folders.isEmpty()) {
                                    Text("No folders scanned yet. Tap Scan below.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (filteredFolders.isEmpty()) {
                                    Text("No matching folders found.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                        items(filteredFolders) { folder ->
                                            val isChecked = selectedFolders[folder] ?: false
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectedFolders[folder] = !isChecked },
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { selectedFolders[folder] = it }
                                                )
                                                Text(folder, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ActionHint("Add selected to queue") { IconButton(
                                        onClick = {
                                            val idsToQueue = mutableListOf<Long>()
                                            allVideos.forEach { v ->
                                                val fKey = mediaFolderLabel(v)
                                                if (selectedFolders[fKey] == true && v.reviewDecision == "keep") {
                                                    idsToQueue += v.id
                                                }
                                            }
                                            viewModel.enqueueVideos(idsToQueue)
                                        },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Add selected to queue")
                                    } }
                                }
                            } else if (activePanelSection == "settings") {
                                // Mini quick-settings panel in sidebar
                                Text("AI setup", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                                ActionHint("Open AI settings") { IconButton(onClick = { settingsModalState = WindowState.STANDARD }, modifier = Modifier.align(Alignment.End)) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = "Open AI settings")
                                } }
                            } else {
                                // Default status count inside sidebar
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Summary", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("• Available media: ${allVideos.count { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }}", fontSize = 11.sp)
                                        Text("• Files in queue: ${filteredVideos.size}", fontSize = 11.sp)
                                        Text("• AI service: ${viewModel.llmProvider.value.replace("_", " ").uppercase()}", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    },

                    content = {
                        // Main Content Panel (Depends on Active Navigation Section)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = Presentation.railInset, end = 12.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                        // Header info of active view
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = destinationLabel(activePanelSection),
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            // Sort Controls (visible on backlog & archive)
                            if (activePanelSection == "backlog" || activePanelSection.endsWith("_archive")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val currentSort by viewModel.selectedSort.collectAsState()
                                    listOf(
                                        "Name" to Icons.Default.SortByAlpha,
                                        "Status" to Icons.Default.FilterList,
                                        "Size" to Icons.Default.Straighten
                                    ).forEach { (sort, icon) ->
                                        AppIconAction(icon, "Sort by ${sort.lowercase()}", { viewModel.selectSort(sort) },
                                            selected = currentSort == sort)
                                    }
                                }
                            }
                        }

                        // Live batch processing visual track
                        if (batchState != VideoProcessor.State.IDLE) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (batchState == VideoProcessor.State.PROCESSING) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                            } else {
                                                Icon(if (batchState == VideoProcessor.State.FINISHED) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                                    contentDescription = if (batchState == VideoProcessor.State.FINISHED) "Batch finished" else "Batch paused",
                                                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Batch: ${batchState.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        Row {
                                            if (batchState == VideoProcessor.State.PROCESSING) {
                                                ActionHint("Pause") { IconButton(onClick = { viewModel.pauseBatch() }, modifier = Modifier.size(48.dp)) {
                                                    Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary)
                                                } }
                                            } else if (batchState == VideoProcessor.State.PAUSED) {
                                                ActionHint("Resume") { IconButton(onClick = { viewModel.resumeBatch() }, modifier = Modifier.size(48.dp)) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary)
                                                } }
                                            }
                                        }
                                    }
                                    if (currentStep.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(currentStep, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(progress = { currentProgress / 100f }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }

when (activePanelSection) {
                            "home" -> {
                                OverviewGrid(allVideos = allVideos, mediaState = mediaAccess, ollamaState = ollamaState, model = viewModel.settings.collectAsState().value.ollamaModelName, endpoint = viewModel.settings.collectAsState().value.ollamaEndpointUrl)
                                
                                if (mediaAccess == "Limited access" && !dismissLimitedBanner) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("Limited Access Enabled", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                            Text("Android has restricted gallery scans. Headline totals include only media currently visible to this app.", fontSize = 11.sp)
                                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                ActionHint("Change") { TextButton(onClick = { triggerPermissionCheck() }) { Icon(Icons.Default.Tune, contentDescription = "Change") } }
                                                ActionHint("Dismiss") { TextButton(onClick = { dismissLimitedBanner = true }) { Icon(Icons.Default.Close, contentDescription = "Dismiss") } }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                ActionButtonsBar(
                                    onScan = { triggerPermissionCheck() },
                                    onProcessNext = {
                                        val next = allVideos.firstOrNull {
                                            MediaAnalysisPolicy.canAnalyze(it) && it.status in setOf("new", "queued", "error")
                                        }
                                        if (next != null) {
                                            viewModel.processSingle(next)
                                        } else {
                                            Toast.makeText(context, "Mark media Keep before analysis.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onStartBatch = { viewModel.startBatch() },
                                    onPauseBatch = { viewModel.pauseBatch() },
                                    isScanning = isScanning
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth().clickable { showEngineGuide = !showEngineGuide }, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Guide", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Icon(
                                                imageVector = if (showEngineGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (showEngineGuide) "Collapse guide" else "Expand guide"
                                            )
                                        }
                                        if (showEngineGuide) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("Text is read on your device. Review files before sending them to your selected AI service.", fontSize = 11.sp, lineHeight = 15.sp)
                                        }
                                    }
                                }
                            }

                            "sources" -> {
                                AnimatedContent(targetState = sourceTool, label = "sourceTool") { tool ->
                                    when (tool) {
                                        "rules" -> RulesScreen(
                                            rules = mediaRules,
                                            message = ruleMessage,
                                            onBack = { sourceTool = null },
                                            onSave = viewModel::saveRule,
                                            onEnable = viewModel::setRuleEnabled,
                                            onPreview = viewModel::previewRule,
                                            onRun = viewModel::runRule,
                                            onDelete = viewModel::deleteRule
                                        )
                                        "tags" -> TagsScreen(
                                            tags = customTags,
                                            message = ruleMessage,
                                            assignments = tagAssignments,
                                            media = allVideos,
                                            onBack = { sourceTool = null },
                                            onAdd = viewModel::addTag,
                                            onRename = viewModel::renameTag,
                                            onDelete = viewModel::deleteTag,
                                            onAssign = viewModel::assignTag,
                                            onUnassign = viewModel::unassignTag
                                        )
                                        "bin" -> BinScreen(
                                            media = binMedia,
                                            message = ruleMessage,
                                            onBack = { sourceTool = null },
                                            onRecover = viewModel::recoverBin,
                                            loadThumbnail = viewModel::loadThumbnail,
                                            onDeleteAll = {
                                                viewModel.createBinDeletePendingIntent()?.let { pending ->
                                                    binDeleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                                                }
                                            }
                                        )
                                        else -> MediaSourcesScreen(
                                            totals = inventoryTotals,
                                            aggregates = sourceAggregates,
                                            selectedCategory = selectedSourceCategory,
                                            subcategories = selectedSourceSubcategories,
                                            allMedia = allVideos,
                                            mediaAccess = mediaAccess,
                                            isScanning = isScanning,
                                            onScan = { triggerPermissionCheck() },
                                            onSelectCategory = viewModel::selectSourceCategory,
                                            onDecision = viewModel::setReviewDecision,
                                            onOpenRules = { sourceTool = "rules" },
                                            onOpenTags = { sourceTool = "tags" },
                                            onOpenBin = { sourceTool = "bin" }
                                        )
                                    }
                                }
                            }

                            "folders" -> {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                val context = LocalContext.current
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Folders to scan", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            }
                                            val folderPickerLauncher = rememberLauncherForActivityResult(
                                                contract = ActivityResultContracts.OpenDocumentTree()
                                            ) { uri ->
                                                if (uri != null) {
                                                    try {
                                                        context.contentResolver.takePersistableUriPermission(
                                                            uri,
                                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("MainActivity", "Failed to take persistable URI permission", e)
                                                    }
                                                    viewModel.addTargetFolder(uri.toString())
                                                }
                                            }
                                            ActionHint("Add watch folder") { TextButton(
                                                onClick = { folderPickerLauncher.launch(null) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Add watch folder", modifier = Modifier.size(16.dp))
                                            } }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        val targetFoldersList by viewModel.targetFolders.collectAsState()
                                        if (targetFoldersList.isNotEmpty()) {
                                            Text("Searches stay inside these folders and their subfolders. Tap Scan to update the list.", fontSize = 11.sp)
                                        }
                                        if (targetFoldersList.isEmpty()) {
                                            Text(
                                                "No folders selected. All phone folders are included.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Normal
                                            )
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                targetFoldersList.forEach { folderUri ->
                                                    val pathName = Uri.parse(folderUri).path?.substringAfterLast(":") ?: "Folder"
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                                                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(pathName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                                                        }
                                                        ActionHint("Remove") { IconButton(
                                                            onClick = { viewModel.removeTargetFolder(folderUri) },
                                                            modifier = Modifier.size(48.dp)
                                                        ) {
                                                            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                        } }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Auto add", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                                Text("Find new screenshots and recordings while the app is open. Review them before processing.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(
                                                checked = autoIngestEnabled,
                                                onCheckedChange = { viewModel.setAutoIngestEnabled(it) }, modifier = Modifier.testTag("auto_add")
                                            )
                                        }
                                        
                                        if (autoIngestEnabled) {
                                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                                            Text("File types", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewModel.setAutoIngestRecordings(!autoIngestRecordings) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = autoIngestRecordings,
                                                    onCheckedChange = { viewModel.setAutoIngestRecordings(it) }, modifier = Modifier.testTag("auto_record")
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text("Screen recordings", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                    Text("Find names containing 'screen', 'record', or 'cast'.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { viewModel.setAutoIngestScreenshots(!autoIngestScreenshots) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = autoIngestScreenshots,
                                                    onCheckedChange = { viewModel.setAutoIngestScreenshots(it) }, modifier = Modifier.testTag("auto_shots")
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text("Screenshots", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                                    Text("Find names containing 'screenshot'.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.Info,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        "Auto add only finds files. You choose what to process.",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                }
                            }

                            "backlog" -> {
                                val selectedBacklogVideos = remember { mutableStateMapOf<Long, Boolean>() }
                                val backlogList = remember(filteredVideos) { filteredVideos.filter { it.status != "archived" } }
                                val allSelected = backlogList.isNotEmpty() && backlogList.all { selectedBacklogVideos[it.id] == true }

                                Column(modifier = Modifier.fillMaxSize()) {
                                    val selectedIds = backlogList.filter { selectedBacklogVideos[it.id] == true }.map { it.id }
                                    if (selectedIds.isNotEmpty()) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text("${selectedIds.size} ${if (selectedIds.size == 1) "file" else "files"} selected", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                    ActionHint("Process selected") { TextButton(
                                                        onClick = {
                                                            val keptItems = selectedIds.mapNotNull { id -> backlogList.find { it.id == id } }
                                                                .filter { it.reviewDecision == "keep" }
                                                            keptItems.forEach(viewModel::processSingle)
                                                            Toast.makeText(context, "Processing started for ${keptItems.size} kept files.", Toast.LENGTH_SHORT).show()
                                                            selectedBacklogVideos.clear()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(48.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = "Process selected", modifier = Modifier.size(14.dp))
                                                    } }

                                                    ActionHint("Archive selected") { TextButton(
                                                        onClick = {
                                                            selectedIds.forEach { id ->
                                                                viewModel.archiveVideo(id)
                                                            }
                                                            Toast.makeText(context, "Archived ${selectedIds.size} files!", Toast.LENGTH_SHORT).show()
                                                            selectedBacklogVideos.clear()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(48.dp)
                                                    ) {
                                                        Icon(Icons.Default.Archive, contentDescription = "Archive selected", modifier = Modifier.size(14.dp))
                                                    } }

                                                    ActionHint("Cancel") { TextButton(
                                                        onClick = { selectedBacklogVideos.clear() },
                                                        modifier = Modifier.height(48.dp)
                                                    ) { Icon(Icons.Default.Close, contentDescription = "Cancel") } }
                                                }
                                            }
                                        }
                                    }

                                    // Modern Table Format for Backlog
                                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                                        BoxWithConstraints(Modifier.fillMaxSize()) {
                                            val queueViewportWidth = maxWidth
                                            val queueTableWidth = queueViewportWidth

                                            Column(Modifier.fillMaxSize()) {
                                                Box(Modifier.weight(1f).fillMaxWidth().testTag("queue_table_viewport")) {
                                                    Column(Modifier.width(queueTableWidth).fillMaxHeight().testTag("queue_table_content")) {
                                            // Table Header
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = allSelected,
                                                    onCheckedChange = { checked -> backlogList.forEach { selectedBacklogVideos[it.id] = checked } },
                                                    modifier = Modifier.size(48.dp).testTag("queue_select_all")
                                                )
                                                Text("Select all", fontSize = 12.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("${backlogList.size} ${if (backlogList.size == 1) "file" else "files"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }

                                            if (backlogList.isEmpty()) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Text("Queue is empty. Find files or clear filters.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            } else {
                                                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    itemsIndexed(backlogList, key = { _, video -> video.id }) { index, video ->
                                                        val loc = Uri.parse(video.uriString).path?.substringBeforeLast("/", "Internal") ?: "Internal"
                                                        val cleanLoc = if (loc.contains(":")) loc.substringAfterLast(":") else loc
                                                        val shortLoc = if (cleanLoc.length > 10) cleanLoc.take(10) + ".." else cleanLoc
                                                        val sizeMB = formatMediaBytes(video.sizeBytes ?: 0L)
                                                        val statBadge = when (video.status) {
                                                            "new" -> "New"
                                                            "queued" -> "Queued"
                                                            "processing" -> "Processing"
                                                            "processed" -> "Saved"
                                                            "error" -> "Failed"
                                                            "ready_to_delete" -> "Review"
                                                            else -> "Waiting"
                                                        }
                                                        val badgeBg = when (statBadge) {
                                                            "Done" -> MaterialTheme.colorScheme.tertiaryContainer
                                                            "Failed" -> MaterialTheme.colorScheme.errorContainer
                                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                                        }
                                                        val badgeColor = when (statBadge) {
                                                            "Done" -> MaterialTheme.colorScheme.onTertiaryContainer
                                                            "Failed" -> MaterialTheme.colorScheme.onErrorContainer
                                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                        val isChecked = selectedBacklogVideos[video.id] ?: false

                                                        val rowBg = if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

                                                        Column(
                                                            modifier = Modifier.fillMaxWidth()
                                                                .animateContentSize()
                                                                .background(if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else rowBg)
                                                                .then(Bottom(1.dp, MaterialTheme.colorScheme.outlineVariant))
                                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                                                .testTag("queue_row_${video.id}")
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Checkbox(
                                                                    checked = isChecked,
                                                                    onCheckedChange = { selectedBacklogVideos[video.id] = it },
                                                                    modifier = Modifier.size(48.dp)
                                                                )
                                                                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                                                    Text(video.displayName, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                                                                    Text(mediaFolderLabel(video), fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                                }
                                                                Column(Modifier.widthIn(max = 86.dp), horizontalAlignment = Alignment.End) {
                                                                    Text(sizeMB, fontSize = 11.sp)
                                                                    Text(statBadge, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                                                        color = if (video.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                                                }
                                                            }
                                                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                                AppIconAction(Icons.Default.AutoFixHigh, "Rename", { viewModel.triggerFileRenameWithLLM(video.id) },
                                                                    Modifier.testTag("queue_${video.id}_rename"), hint = "Suggest a file name")
                                                                // Open folder location
                                                                AppIconAction(Icons.Default.Folder, "Folder", {
                                                                    Toast.makeText(context, "Location: ${video.uriString}", Toast.LENGTH_LONG).show()
                                                                }, Modifier.testTag("queue_${video.id}_folder"), hint = "Show file location")
                                                                // Open processed output
                                                                AppIconAction(Icons.Default.OpenInNew, "Output", {
                                                                    outputModalVideoId = video.id
                                                                    outputModalState = WindowState.STANDARD
                                                                }, Modifier.testTag("queue_${video.id}_output"), enabled = video.status == "processed",
                                                                    hint = if (video.status == "processed") "Open saved result" else "No saved result yet")
                                                                // Open preview modal
                                                                AppIconAction(Icons.Default.Visibility, "Preview", {
                                                                    if (previewModalVideoId != video.id || previewModalState == null) previewDrafts.removeState("preview:${video.id}")
                                                                    previewModalVideoId = video.id
                                                                    previewModalState = WindowState.STANDARD
                                                                }, Modifier.testTag("queue_${video.id}_preview"), hint = "Preview file")
                                                                // Run processing
                                                                AppIconAction(Icons.Default.Refresh, "Process", {
                                                                    viewModel.processSingle(video)
                                                                    Toast.makeText(context, "Processing started for kept media.", Toast.LENGTH_SHORT).show()
                                                                }, Modifier.testTag("queue_${video.id}_process"), enabled = video.reviewDecision == "keep",
                                                                    hint = if (video.reviewDecision == "keep") "Process this file" else "Mark this file Keep before processing")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "video_archive", "image_archive" -> {
                                // ARCHIVED FILES MANAGEMENT VIEW
                                val archivedItems = remember(allVideos, activePanelSection, archiveSort) {
                                    val archived = allVideos.filter {
                                        it.isCurrentlyAvailable && !it.isPending && !it.isTrashed && it.status == "archived" && if (activePanelSection == "image_archive") {
                                            it.mimeType?.startsWith("image/") == true
                                        } else {
                                            it.mimeType?.startsWith("video/") == true
                                        }
                                    }
                                    when (archiveSort) {
                                        "Name" -> archived.sortedBy { it.displayName.lowercase() }
                                        "Status" -> archived.sortedBy { it.status }
                                        "Size" -> archived.sortedBy { it.sizeBytes ?: 0L }
                                        else -> archived
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val allSelected = archivedItems.isNotEmpty() && archivedItems.all { selectedArchiveVideos[it.id] == true }
                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = { check ->
                                            archivedItems.forEach { selectedArchiveVideos[it.id] = check }
                                        }
                                    )
                                    Text("Location", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("File", modifier = Modifier.weight(2.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text("Size", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                val archivedList = archivedItems

                                if (archivedList.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No processed files found in Archive.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        LazyColumn(modifier = Modifier.weight(1f)) {
                                            items(archivedList, key = { it.id }) { video ->
                                                val loc = Uri.parse(video.uriString).path?.substringBeforeLast("/", "Internal") ?: "Internal"
                                                val cleanLoc = if (loc.contains(":")) loc.substringAfterLast(":") else loc
                                                val shortLoc = if (cleanLoc.length > 10) cleanLoc.take(10) + ".." else cleanLoc
                                                val sizeMB = formatMediaBytes(video.sizeBytes ?: 0L)
                                                val isChecked = selectedArchiveVideos[video.id] ?: false

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(Bottom(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)))
                                                        .padding(vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Checkbox(
                                                        checked = isChecked,
                                                        onCheckedChange = { selectedArchiveVideos[video.id] = it }
                                                    )
                                                    Text(shortLoc, modifier = Modifier.weight(1.5f), fontSize = 11.sp)
                                                    Text(video.displayName, modifier = Modifier.weight(2.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    Text(sizeMB, modifier = Modifier.weight(1f), fontSize = 11.sp)
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ActionHint("Restore selected") { TextButton(
                                                onClick = {
                                                    val selectedIds = archivedItems.filter { selectedArchiveVideos[it.id] == true }.map { it.id }
                                                    selectedIds.forEach { id ->
                                                        viewModel.restoreArchivedVideo(id)
                                                    }
                                                    Toast.makeText(context, "Restored ${selectedIds.size} files back to main backlog!", Toast.LENGTH_SHORT).show()
                                                    selectedArchiveVideos.clear()
                                                },
                                                modifier = Modifier.weight(1f),
                                                enabled = archivedItems.any { selectedArchiveVideos[it.id] == true }
                                            ) {
                                                Icon(Icons.Default.Restore, contentDescription = "Restore selected", modifier = Modifier.size(16.dp))
                                            } }

                                            ActionHint("Delete selected index entries") { TextButton(
                                                onClick = {
                                                    val selectedIds = archivedItems.filter { selectedArchiveVideos[it.id] == true }.map { it.id }
                                                    selectedIds.forEach { id ->
                                                        viewModel.removeVideoFromBacklog(id)
                                                    }
                                                    Toast.makeText(context, "Permanently deleted ${selectedIds.size} index entries from storage mapping!", Toast.LENGTH_SHORT).show()
                                                    selectedArchiveVideos.clear()
                                                },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.weight(1f),
                                                enabled = archivedItems.any { selectedArchiveVideos[it.id] == true }
                                            ) {
                                                Icon(Icons.Default.DeleteForever, contentDescription = "Delete selected index entries", modifier = Modifier.size(16.dp))
                                            } }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                )
            }

            // Preserve the original edge-handle interaction while the current screen moves off-screen.
            ActionHint(if (leftPanelExpanded) "Close menu" else "Open menu",
                modifier = Modifier.align(Alignment.CenterStart).offset(x = if (leftPanelExpanded) 240.dp else 0.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { leftPanelExpanded = !leftPanelExpanded }
                    .padding(horizontal = 6.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (leftPanelExpanded) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                    contentDescription = if (leftPanelExpanded) "Close menu" else "Open menu",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            }

            // Minimized Modals floating bar badges
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (previewModalState == WindowState.MINIMIZED) {
                    Card(
                        modifier = Modifier
                            .clickable { previewModalState = WindowState.STANDARD }
                            .padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Preview, contentDescription = "Restore preview", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (outputModalState == WindowState.MINIMIZED) {
                    Card(
                        modifier = Modifier
                            .clickable { outputModalState = WindowState.STANDARD }
                            .padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Article, contentDescription = "Restore output", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (settingsModalState == WindowState.MINIMIZED) {
                    Card(
                        modifier = Modifier
                            .clickable { settingsModalState = WindowState.STANDARD }
                            .padding(4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Settings, contentDescription = "Restore settings", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Windowed Stateful Dialog Overlay Renderers
            if (previewModalState != null && previewModalState != WindowState.MINIMIZED) {
                val pVideoId = previewModalVideoId
                val vItem = allVideos.find { it.id == pVideoId }
                if (vItem != null) {
                    WindowedModal(
                        title = "Media Details Preview: ${vItem.displayName}",
                        windowState = previewModalState!!,
                        onStateChange = { previewModalState = it }
                    ) {
                        previewDrafts.SaveableStateProvider("preview:$pVideoId") {
                        val framesList by remember(vItem.id) { viewModel.framesForVideo(vItem.id) }.collectAsState(initial = emptyList())
                        val analysisObj = allAnalysisResults[pVideoId]
                        VideoDetailPanel(
                            video = vItem,
                            frames = framesList, // fetches dynamically via viewModel
                            analysis = analysisObj,
                            onClose = { previewModalState = null },
                            onProcessSingle = { viewModel.processSingle(vItem) },
                            onResetResult = { viewModel.resetVideo(vItem.id) },
                            onArchive = { viewModel.archiveVideo(vItem.id) },
                            onToggleDeleteCandidate = { viewModel.toggleReadyToDelete(vItem.id) },
                            onSaveEdits = { notes, t1, t2, c1, c2, c3, road ->
                                viewModel.saveEdits(vItem.id, notes, t1, t2, c1, c2, c3, road)
                                Toast.makeText(context, "Edits Saved!", Toast.LENGTH_SHORT).show()
                            },
                            onDeleteFromBacklog = {
                                viewModel.removeVideoFromBacklog(vItem.id)
                                previewModalState = null
                            },
                            onMoveToTrash = {
                                requestStorageAction(vItem.id, true)
                            },
                            onPermanentDelete = {
                                requestStorageAction(vItem.id, false)
                            }
                        )
                        }
                    }
                }
            }

            if (outputModalState != null && outputModalState != WindowState.MINIMIZED) {
                val oVideoId = outputModalVideoId
                val vItem = allVideos.find { it.id == oVideoId }
                val analysisObj = allAnalysisResults[oVideoId]
                if (vItem != null) {
                    WindowedModal(
                        title = "Processed Result Summary: ${vItem.displayName}",
                        windowState = outputModalState!!,
                        onStateChange = { outputModalState = it }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("A.I. Structured Content Map", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Category path:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("${analysisObj?.categoryLevel1 ?: "General"} > ${analysisObj?.categoryLevel2 ?: "Default"} > ${analysisObj?.categoryLevel3 ?: "Workflow"}", fontSize = 13.sp)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Summary:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(analysisObj?.descriptionLine1 ?: "None generated yet.", fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Details:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(analysisObj?.descriptionLine2 ?: "None generated yet.", fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Action plan Roadmap / Commands", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.05f))
                                    .padding(8.dp)
                            ) {
                                Text(analysisObj?.formattedActionPlan ?: "No commands indexed in this scene.", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            ActionHint("Done") { TextButton(onClick = { outputModalState = null }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Check, contentDescription = "Done") } }
                        }
                    }
                }
            }

            if (settingsModalState != null && settingsModalState != WindowState.MINIMIZED) {
                WindowedModal(
                    title = "AI settings",
                    windowState = settingsModalState!!,
                    onStateChange = { if (it == null) settingsApiKeyDraft = ""; settingsModalState = it },
                    secure = true
                ) {
                    settingsDrafts.SaveableStateProvider("settings") {
                    var editProvider by rememberSaveable { mutableStateOf(llmProvider) }
                    val editApiKey = settingsApiKeyDraft
                    var editTemperature by rememberSaveable { mutableStateOf(llmTemperature) }
                    var editPrompt by rememberSaveable { mutableStateOf(systemPrompt) }
                    
                    val settingsObj = viewModel.settings.collectAsState().value
                    var editUrl by rememberSaveable { mutableStateOf(settingsObj.ollamaEndpointUrl) }
                    var editTimeout by rememberSaveable { mutableStateOf(settingsObj.requestTimeoutSeconds.toString()) }
                    var outsideServiceAllowed by rememberSaveable { mutableStateOf(settingsObj.confirmedNonLocalEndpointWarning) }
                    var selectedModelName by rememberSaveable { mutableStateOf(settingsObj.ollamaModelName) }

                    val connSuccess by viewModel.connectionSuccess.collectAsState()
                    val modelsProvider by viewModel.availableModelProvider.collectAsState()
                    val modelsEndpoint by viewModel.availableModelEndpoint.collectAsState()
                    val modelDiscoveryMessage by viewModel.modelDiscoveryMessage.collectAsState()
                    var connectionCheckedFor by rememberSaveable { mutableStateOf<String?>(null) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val displayModels = if (modelsProvider == editProvider && (editProvider == "local" || modelsEndpoint == viewModel.modelEndpointKey(editUrl))) availableModels else emptyList()

                        LaunchedEffect(editProvider, editUrl, availableModels, modelsProvider, modelsEndpoint) {
                            if (selectedModelName.isBlank() && displayModels.isNotEmpty()) {
                                selectedModelName = displayModels.firstOrNull() ?: ""
                            }
                        }

                        var providerExpanded by remember { mutableStateOf(false) }

                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI setup", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            
                            ExposedDropdownMenuBox(
                                expanded = providerExpanded,
                                onExpandedChange = { providerExpanded = !providerExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = when(editProvider) {
                                        "ollama_local" -> "PC"
                                        "ollama_cloud" -> "Cloud"
                                                                                "local" -> "Device"
                                        else -> "PC"
                                    },
                                    onValueChange = {},
                                    readOnly = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                                    },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    singleLine = true
                                )
                                ExposedDropdownMenu(
                                    expanded = providerExpanded,
                                    onDismissRequest = { providerExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("PC") },
                                        onClick = { editProvider = "ollama_local"; selectedModelName = ""; providerExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Cloud") },
                                        onClick = { editProvider = "ollama_cloud"; editUrl = "https://ollama.com"; selectedModelName = ""; providerExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Device") },
                                        onClick = { editProvider = "local"; selectedModelName = ""; providerExpanded = false }
                                    )
                                }
                            }
                        }

                        // URL Endpoint configuration (Only for Ollama providers)
                        if (editProvider != "local") {
                            OutlinedTextField(
                                value = editUrl,
                                onValueChange = { editUrl = it; selectedModelName = ""; outsideServiceAllowed = false },
                                label = { Text("AI address") },
                                modifier = Modifier.testTag("settings_address").fillMaxWidth()
                            )

                            if (editProvider == "ollama_local" && viewModel.endpointNeedsConsent(editUrl)) {
                                Text("This address can send detected words and notes outside your computer. Without permission, summaries use Device fallback.")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = outsideServiceAllowed, onCheckedChange = { outsideServiceAllowed = it }, modifier = Modifier.testTag("allow_outside_service"))
                                    Text("Allow this outside service")
                                }
                            }

                            // Connection Testing Action Row
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ActionHint("Test connection") { TextButton(onClick = {
                                    connectionCheckedFor = "$editProvider|$editUrl"
                                    viewModel.checkConnection(editUrl, editProvider, editApiKey.takeIf { it.isNotBlank() })
                                }) {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = "Test connection")
                                } }
                                Spacer(modifier = Modifier.width(12.dp))
                                if (connectionCheckedFor == "$editProvider|$editUrl" && connSuccess == true) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Succeeded", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (editProvider == "ollama_cloud") "Service reachable" else "Connected", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else if (connectionCheckedFor == "$editProvider|$editUrl" && connSuccess == false) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Unreachable", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Icon(Icons.Default.HelpOutline, contentDescription = "Unknown", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        modelDiscoveryMessage?.let { Text(it, fontSize = 12.sp) }

                        // Model tags syncing dropdown
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("AI model", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            ActionHint("Sync models tag list") { IconButton(onClick = { viewModel.syncModels(editUrl, editProvider, editApiKey.takeIf { it.isNotBlank() }) }) {
                                Icon(Icons.Default.Sync, contentDescription = "Sync models tag list")
                            } }
                        }
                        
                        var modelExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = !modelExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedModelName,
                                onValueChange = {},
                                placeholder = { Text(if (displayModels.isEmpty()) "Tap sync to fetch models" else "Select model") },
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                                },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false }
                            ) {
                                if (displayModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No model") },
                                        onClick = { modelExpanded = false }
                                    )
                                } else {
                                    displayModels.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text(m) },
                                            onClick = {
                                                selectedModelName = m
                                                modelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = editTimeout,
                            onValueChange = { editTimeout = it },
                            label = { Text("Wait limit") },
                            supportingText = { Text("5–300 seconds") },
                            isError = editTimeout.toIntOrNull() !in 5..300,
                            modifier = Modifier.testTag("settings_wait_limit").fillMaxWidth()
                        )
                        if (editProvider == "local") {
                            Text("Uses simple word matching on this phone. No AI model is installed.")
                        }

                        // Temperature Slider
                        Column {
                            Text("Answer variety", fontSize = 12.sp)
                            Text("%.2f".format(editTemperature), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = editTemperature,
                                onValueChange = { editTemperature = it },
                                valueRange = 0.0f..1.0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // API Key Field for Cloud providers
                        if (editProvider == "ollama_cloud") {
                            OutlinedTextField(
                                value = editApiKey,
                                onValueChange = { settingsApiKeyDraft = it },
                                label = { Text("Access key") },
                                supportingText = { Text(if (hasApiKey) "Saved key is hidden. Leave blank to keep it." else "Enter your Cloud key here. It stays hidden.") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
                            )
                        }

                        // Custom System Prompt Area
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Instructions", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                ActionHint("Reset instructions") { IconButton(onClick = { editPrompt = defaultSystemPrompt }) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset instructions")
                                } }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = editPrompt,
                                onValueChange = { editPrompt = it },
                                label = { Text("AI instructions") },
                                modifier = Modifier.testTag("settings_prompt").fillMaxWidth().height(110.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        ActionHint("Save settings") { TextButton(
                            onClick = {
                                viewModel.updateSettings(editProvider == "ollama_local" || editProvider == "ollama_cloud", editUrl, selectedModelName, editTimeout.toInt(), outsideServiceAllowed)
                                viewModel.saveExpandedSettings(editProvider, editApiKey, editTemperature, editPrompt)
                                settingsApiKeyDraft = ""
                                settingsModalState = null
                                Toast.makeText(context, "Configurations sync-saved successfully!", Toast.LENGTH_SHORT).show()
                            },
                            enabled = editTimeout.toIntOrNull() in 5..300,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save settings")
                        } }
                    }
                }
            }
            }
        }
    }
}

// Custom Draw Helper Extensions
private fun Bottom(width: androidx.compose.ui.unit.Dp, color: Color): androidx.compose.ui.Modifier {
    return Modifier.drawWithContent {
        drawContent()
        val strokeWidth = width.toPx()
        val y = size.height - strokeWidth / 2
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
internal fun MediaManagementNavigation(
    activeDestination: String,
    onSelect: (String) -> Unit
) {
    NavigationRowItem("Home", Icons.Default.Home, activeDestination == "home") { onSelect("home") }
    NavigationRowItem("Folders", Icons.Default.FolderSpecial, activeDestination == "folders") { onSelect("folders") }
    NavigationRowItem("Sources", Icons.Default.Storage, activeDestination == "sources") { onSelect("sources") }
    NavigationRowItem("Queue", Icons.Default.FormatListBulleted, activeDestination == "backlog") { onSelect("backlog") }
    NavigationRowItem("Videos", Icons.Default.VideoLibrary, activeDestination == "video_archive") { onSelect("video_archive") }
    NavigationRowItem("Images", Icons.Default.Image, activeDestination == "image_archive") { onSelect("image_archive") }
}

private fun destinationLabel(destination: String): String = when (destination) {
    "home" -> "Home"
    "folders" -> "Folders"
    "sources" -> "Sources"
    "backlog" -> "Queue"
    "video_archive" -> "Videos"
    "image_archive" -> "Images"
    else -> "Media"
}

@Composable
fun NavigationRowItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(180), label = "navigation selection"
    )
    val text by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180), label = "navigation emphasis"
    )
    ActionHint(title) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("nav_${title.lowercase()}")
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .heightIn(min = Presentation.target)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = text, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, color = text, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
    }
}

@Composable
fun OverviewGrid(
    allVideos: List<VideoItem>,
    mediaState: String,
    ollamaState: String,
    model: String = "",
    endpoint: String = ""
) {
    var isExpanded by remember { mutableStateOf(false) } // Default collapsed

    val newCount = allVideos.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.count { it.status == "new" }
    val queuedCount = allVideos.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.count { it.status == "queued" || it.status == "processing" }
    val procCount = allVideos.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.count { it.status == "processed" || it.status == "archived" }
    val reviewCount = allVideos.filter { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }.count { it.status == "ready_to_delete" || it.status == "error" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Stats header row that acts as the toggle
        Row(
            modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Overview", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // High Density Stats Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // New Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "New",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "%02d".format(newCount),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

            // Queued Card
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Queued",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%02d".format(queuedCount),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Proc Card
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Saved",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%02d".format(procCount),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Review Card
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Review",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "%02d".format(reviewCount),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Status & Connectivity Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // LLM Connectivity status
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (ollamaState == "Available") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI: $ollamaState ($model)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = runCatching { Uri.parse(endpoint).authority ?: endpoint }.getOrDefault(endpoint),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Media Library status
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Media: $mediaState",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${allVideos.count { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }} Media Found",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
            } // Close inner Column
        } // Close AnimatedVisibility
    } // Close outer Column
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionButtonsBar(
    onScan: () -> Unit,
    onProcessNext: () -> Unit,
    onStartBatch: () -> Unit,
    onPauseBatch: () -> Unit,
    isScanning: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconAction(Icons.Default.YoutubeSearchedFor, "Scan Videos", onScan, enabled = !isScanning,
            hint = if (isScanning) "Finding files" else "Find photos and videos")
        AppIconAction(Icons.Default.PlayArrow, "Start Batch", onStartBatch, hint = "Process queued files")
        AppIconAction(Icons.Default.SkipNext, "Process Next", onProcessNext, hint = "Process the next file")
        AppIconAction(Icons.Default.Pause, "Pause Batch", onPauseBatch, hint = "Pause processing")
    }
}

@Composable
fun TargetFoldersConfig(
    targetFolders: List<String>,
    onAddFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit
) {
    val context = LocalContext.current
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to take persistable URI permission in TargetFoldersConfig", e)
            }
            onAddFolder(uri.toString())
        }
    }

    var isExpanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Folders", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (targetFolders.isEmpty()) "All" else targetFolders.size.toString(), fontSize = 12.sp)
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Specific folders to scan. If empty, all media is scanned.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                targetFolders.forEach { folderUri ->
                    val pathName = Uri.parse(folderUri).path?.substringAfterLast(":") ?: "Folder"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pathName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ActionHint("Remove") { IconButton(
                            onClick = { onRemoveFolder(folderUri) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        } }
                    }
                }

                ActionHint("Add target folder") { TextButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add target folder", modifier = Modifier.size(18.dp))
                } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDropdown(selectedFilter: String, onSelect: (String) -> Unit) {
    val filters = listOf(
        "All" to "All",
        "New" to "New",
        "Queued" to "Queued",
        "Processed" to "Done",
        "Error" to "Error",
        "Archived" to "Archived",
        "Video Archive" to "Videos",
        "Image Archive" to "Images",
        "Ready to delete" to "Remove"
    )
    val selectedLabel = filters.firstOrNull { it.first == selectedFilter }?.second ?: "All"
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Filter") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            filters.forEach { (filter, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(filter)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun VideoItemCard(
    video: VideoItem,
    isProcessing: Boolean,
    analysis: AnalysisResult? = null,
    onClick: () -> Unit,
    onToggleQueue: () -> Unit
) {
    val isReadyToDelete = video.status == "ready_to_delete"
    val isError = video.status == "error"
    val isProcessed = video.status == "processed"
    val isQueued = video.status == "queued" || video.status == "processing"

    // Card background configuration
    val cardBg = if (isReadyToDelete || isError) {
        MaterialTheme.colorScheme.surface
    } else if (isProcessing) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.White
    }

    val cardBorder = if (isReadyToDelete || isError) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.errorContainer)
    } else if (isProcessing) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("video_item_card_${video.id}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail container box
            val leadingBoxBg = if (isReadyToDelete || isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            val iconColor = if (isReadyToDelete || isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(leadingBoxBg),
                contentAlignment = Alignment.Center
            ) {
                val leadIcon = when (video.status) {
                    "processed" -> Icons.Default.CheckCircle
                    "archived" -> Icons.Default.Inventory
                    "ready_to_delete" -> Icons.Default.DeleteSweep
                    "error" -> Icons.Default.Error
                    "processing" -> Icons.Default.Autorenew
                    else -> Icons.Default.VideoFile
                }
                Icon(
                    imageVector = leadIcon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text details column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                val dateFormatted = remember(video.dateModified) {
                    if (video.dateModified != null) {
                        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        sdf.format(Date(video.dateModified * 1000L))
                    } else "N/A"
                }

                Text(
                    text = "${formatDuration(video.durationMs ?: 0)}s • $dateFormatted",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Badges row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val badgeBg = when (video.status) {
                        "processed" -> MaterialTheme.colorScheme.secondaryContainer
                        "queued", "processing" -> MaterialTheme.colorScheme.surfaceVariant
                        "ready_to_delete", "error" -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                    val badgeText = when (video.status) {
                        "processed" -> MaterialTheme.colorScheme.onSecondaryContainer
                        "queued", "processing" -> MaterialTheme.colorScheme.onSurfaceVariant
                        "ready_to_delete", "error" -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = video.status.uppercase(Locale.getDefault()).replace("_", " "),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeText
                        )
                    }

                    if (analysis != null && analysis.categoryLevel1.isNotEmpty()) {
                        val categoryPath = if (analysis.categoryLevel2.isNotEmpty()) {
                            "${analysis.categoryLevel1} > ${analysis.categoryLevel2}"
                        } else {
                            analysis.categoryLevel1
                        }
                        Text(
                            text = categoryPath,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Tail interaction buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionHint("Add or remove from queue") { IconButton(onClick = onToggleQueue) {
                    val queueIcon = if (video.status == "queued") Icons.Default.Star else Icons.Default.StarBorder
                    val queueColor = if (video.status == "queued") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(imageVector = queueIcon, contentDescription = "Add or remove from queue", tint = queueColor)
                } }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VideoDetailPanel(
    video: VideoItem,
    frames: List<ExtractedFrame>,
    analysis: AnalysisResult?,
    onClose: () -> Unit,
    onProcessSingle: () -> Unit,
    onResetResult: () -> Unit,
    onArchive: () -> Unit,
    onToggleDeleteCandidate: () -> Unit,
    onSaveEdits: (notes: String, t1: String, t2: String, c1: String, c2: String, c3: String, roadmap: String) -> Unit,
    onDeleteFromBacklog: () -> Unit,
    onMoveToTrash: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    var manualNotes by rememberSaveable(video.id) { mutableStateOf(video.manualTranscript ?: "") }
    
    // Editable analysis states
    var title1 by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.descriptionLine1 ?: "Deterministically Analyzed.") }
    var title2 by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.descriptionLine2 ?: "Ready for review.") }
    var cat1 by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.categoryLevel1 ?: "Technical") }
    var cat2 by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.categoryLevel2 ?: "General") }
    var cat3 by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.categoryLevel3 ?: "Workflow") }
    var roadmapPlan by rememberSaveable(video.id, analysis?.id) { mutableStateOf(analysis?.formattedActionPlan ?: "") }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionHint("Back") { IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                } }
                Text(
                    text = "Video Analysis Details",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metadata Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = video.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "URI: ${video.uriString}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "Mime: ${video.mimeType}  |  Bytes: ${video.sizeBytes}", fontSize = 11.sp)
                    Text(text = "Status: ${video.status.uppercase()}  |  Completed step: ${video.lastCompletedStep}", fontSize = 11.sp)
                    if (video.mimeType == "image/gif") Text("Animated images: only the first frame is read.", fontSize = 11.sp)
                }
            }
            
            if (video.status == "error" && video.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Processing Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
                            val clipboardManager = LocalClipboardManager.current
                            ActionHint("Copy Error") { IconButton(onClick = { clipboardManager.setText(AnnotatedString(video.errorMessage)) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Error", tint = MaterialTheme.colorScheme.onErrorContainer)
                            } }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(video.errorMessage, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Check the AI service address in Settings, then try again.", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Operations quick buttons
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ActionHint("Process file") { TextButton(onClick = onProcessSingle) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Process file", modifier = Modifier.size(18.dp))
                } }
                ActionHint("Clear saved result") { TextButton(onClick = onResetResult) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Clear saved result", modifier = Modifier.size(18.dp))
                } }
                ActionHint("Archive media") { TextButton(onClick = onArchive) {
                    Icon(imageVector = Icons.Default.Archive, contentDescription = "Archive media", modifier = Modifier.size(18.dp))
                } }
                val deleteCandText = if (video.status == "ready_to_delete") "Unmark Trash Candidate" else "Mark Trash Candidate"
                val deleteCandColor = if (video.status == "ready_to_delete") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                TextButton(
                    onClick = onToggleDeleteCandidate,
                    colors = ButtonDefaults.textButtonColors(contentColor = deleteCandColor)
                ) {
                    Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = deleteCandText, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // User notes & transcript input box
            Text(
                text = "Notes and spoken words",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = manualNotes,
                onValueChange = { manualNotes = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                placeholder = { Text("Add notes, spoken words, or useful details.", fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 13.sp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // OCR Timetable evidence
            Text(
                text = "Scene Text Evidence Timeline (OCR)",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (frames.isEmpty()) {
                Text(
                    text = "No on-screen frame text extracted yet. (Run processing to build timeline)",
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        frames.forEach { frame ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = formatTimestamp(frame.timestampMs),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = frame.ocrText,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Model categorization & parsed info
            Text(
                text = "Knowledge Structure & Category Actions",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cat1,
                    onValueChange = { cat1 = it },
                    label = { Text("Group 1", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = cat2,
                    onValueChange = { cat2 = it },
                    label = { Text("Group 2", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = cat3,
                    onValueChange = { cat3 = it },
                    label = { Text("Group 3", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = title1,
                onValueChange = { title1 = it },
                label = { Text("Summary") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 13.sp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = title2,
                onValueChange = { title2 = it },
                label = { Text("Details") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 13.sp)
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(text = "Action Roadmap / Notes Markdown Map", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = roadmapPlan,
                onValueChange = { roadmapPlan = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )

            if (analysis != null) {
                Spacer(modifier = Modifier.height(10.dp))
                if (analysis.urls.isNotEmpty()) {
                    Text(text = "Extracted Links", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    analysis.urls.forEach { url ->
                        Text(text = url, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
                if (analysis.codeOrCommands.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Extracted Tech / Command Snippets", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    analysis.codeOrCommands.forEach { code ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.05f))
                                .padding(6.dp)
                        ) {
                            Text(text = code, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionHint("Save edits") { TextButton(
                    onClick = {
                        onSaveEdits(manualNotes, title1, title2, cat1, cat2, cat3, roadmapPlan)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save edits", modifier = Modifier.size(18.dp))
                } }
                ActionHint("Remove index entry") { TextButton(
                    onClick = onDeleteFromBacklog,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlaylistRemove, contentDescription = "Remove index entry", modifier = Modifier.size(18.dp))
                } }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            // Extreme Native clean action handlers
            Text(
                text = "Delete files",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Review files first. Android will ask you to confirm before deleting them.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionHint("Move media to trash") { TextButton(
                    onClick = onMoveToTrash,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Move media to trash", modifier = Modifier.size(18.dp))
                } }

                ActionHint("Delete media forever") { TextButton(
                    onClick = onPermanentDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.DeleteForever, contentDescription = "Delete media forever", modifier = Modifier.size(18.dp))
                } }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, url: String, model: String, timeout: Int, warnConfirmed: Boolean) -> Unit
) {
    var enabled by remember { mutableStateOf(currentSettings.ollamaEnabled) }
    var url by remember { mutableStateOf(currentSettings.ollamaEndpointUrl) }
    var model by remember { mutableStateOf(currentSettings.ollamaModelName) }
    var timeoutSecs by remember { mutableStateOf(currentSettings.requestTimeoutSeconds.toString()) }
    var confirmedWarn by remember { mutableStateOf(currentSettings.confirmedNonLocalEndpointWarning) }

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local AI settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                if (enabled) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("AI address") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    // Platform quick helpers info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Presets", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            PresetRow("AI Studio Emulator PC Host: http://10.0.2.2:11434/api/generate", "Use emulator host") {
                                url = "http://10.0.2.2:11434/api/generate"
                            }
                            PresetRow("Real Local Wi-Fi PC host: http://192.168.x.x:11434/api/generate", "Use local network host") {
                                url = "http://192.168.0.100:11434/api/generate"
                            }
                            PresetRow("Running locally on Android: http://127.0.0.1:11434/api/generate", "Use device host") {
                                url = "http://127.0.0.1:11434/api/generate"
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("AI model") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    OutlinedTextField(
                        value = timeoutSecs,
                        onValueChange = { timeoutSecs = it },
                        label = { Text("Wait limit") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Non-local warning rule
                    val host = try { URL(url).host } catch(e: Exception) { "" }
                    val isPrivateIp = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
                            (host.startsWith("192.168.") || host.startsWith("10.") || (host.startsWith("172.") && host.split(".")[1].toIntOrNull() in 16..31))

                    if (url.isNotEmpty() && !isPrivateIp) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "WARNING: Public API Selected",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Videos stay on this device. Only extracted text is sent to the configured LLM endpoint. Never silently expose private notes to public interfaces.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = confirmedWarn, onCheckedChange = { confirmedWarn = it })
                                    Text("I agree", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ActionHint("Confirm and save") { TextButton(
                onClick = {
                    val timeoutVal = timeoutSecs.toIntOrNull() ?: 30
                    onSave(enabled, url, model, timeoutVal, confirmedWarn)
                }
            ) {
                Icon(Icons.Default.Save, contentDescription = "Confirm and save")
            } }
        },
        dismissButton = {
            ActionHint("Cancel") { TextButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel") } }
        }
    )
}

@Composable
private fun PresetRow(text: String, actionDescription: String, onUse: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 9.sp, modifier = Modifier.weight(1f))
        ActionHint(actionDescription) { IconButton(onClick = onUse) {
            Icon(Icons.Default.Link, contentDescription = actionDescription)
        } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MediaSourcesScreen(
    totals: MediaInventoryTotals,
    aggregates: List<SourceAggregate>,
    selectedCategory: String?,
    subcategories: List<SubcategoryAggregate>,
    allMedia: List<VideoItem>,
    mediaAccess: String,
    isScanning: Boolean,
    onScan: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onDecision: (Long, String) -> Unit,
    onOpenRules: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onOpenBin: () -> Unit = {}
) {
    var selectedSubcategory by rememberSaveable(selectedCategory) { mutableStateOf<String?>(null) }
    val eligibleMedia = remember(allMedia) {
        allMedia.filter { !it.isPending && !it.isTrashed && it.isCurrentlyAvailable }
    }
    val scopedMedia = remember(eligibleMedia, selectedCategory) {
        if (selectedCategory == null) eligibleMedia else eligibleMedia.filter {
            if (selectedCategory == "Other") it.sourceCategory == null else it.sourceCategory == selectedCategory
        }
    }
    val readyToDelete = remember(scopedMedia) { scopedMedia.filter { it.reviewDecision == "ready_to_delete" } }
    val kept = remember(scopedMedia) { scopedMedia.count { it.reviewDecision == "keep" } }
    val unreviewed = remember(scopedMedia) { scopedMedia.count { it.reviewDecision == "unreviewed" } }
    val scopedBytes = remember(scopedMedia) { scopedMedia.sumOf { it.sizeBytes ?: 0L } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val listHeight = maxHeight
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("media_sources_screen")) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (selectedCategory == null) "Photos and videos" else selectedCategory, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
                    Text(
                        "${if (selectedCategory == null) totals.itemCount else scopedMedia.size.toLong()} files • ${formatMediaBytes(if (selectedCategory == null) totals.totalBytes else scopedBytes)}",
                        fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.testTag("inventory_total")
                    )
                    if (selectedCategory == null) {
                        Text(
                            "Access: $mediaAccess",
                            fontSize = 10.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("inventory_access")
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selectedCategory == null) {
                        ActionHint("Find files") { TextButton(onClick = onScan, enabled = !isScanning, modifier = Modifier.testTag("inventory_scan_button")) {
                            if (isScanning) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = "Find files", modifier = Modifier.size(18.dp))
                        } }
                    } else {
                        ActionHint("Show all sources") { TextButton(onClick = { onSelectCategory(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Show all sources", modifier = Modifier.size(18.dp))
                        } }
                        ActionHint("Find files") { IconButton(onClick = onScan, enabled = !isScanning, modifier = Modifier.testTag("inventory_scan_button")) {
                            if (isScanning) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = "Find files")
                        } }
                    }
                }
            }
        }

        if (selectedCategory == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                ActionHint("Rules") { IconButton(onClick = onOpenRules, modifier = Modifier.testTag("open_rules")) {
                    Icon(Icons.Default.Rule, contentDescription = "Rules")
                } }
                ActionHint("Tags") { IconButton(onClick = onOpenTags, modifier = Modifier.testTag("open_tags")) {
                    Icon(Icons.Default.Label, contentDescription = "Tags")
                } }
                ActionHint("Bin") { IconButton(onClick = onOpenBin, modifier = Modifier.testTag("open_bin")) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Bin")
                } }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedCategory == null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val inline = maxWidth < 360.dp && LocalDensity.current.fontScale > 1.2f
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = if (inline) 1 else 3) {
                    InventoryMetric("To review", unreviewed.toLong(), "Choose Keep or Remove", Modifier.weight(1f), inline)
                    InventoryMetric("Keep", kept.toLong(), "Ready to process", Modifier.weight(1f), inline)
                    InventoryMetric("To remove", readyToDelete.size.toLong(), formatMediaBytes(readyToDelete.sumOf { it.sizeBytes ?: 0L }), Modifier.weight(1f), inline)
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "$unreviewed unreviewed   •   $kept kept   •   ${readyToDelete.size} ready to delete (${formatMediaBytes(readyToDelete.sumOf { it.sizeBytes ?: 0L })})",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedCategory == null) {
            if (aggregates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No photos or videos found", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Find files, then allow photo and video access.", fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                    items(aggregates, key = { it.category }) { aggregate ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCategory(aggregate.category) }
                                .testTag("source_category_${aggregate.category}"),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(sourceIcon(aggregate.category), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(aggregate.category, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${aggregate.itemCount} items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(18.dp))
                                Text(formatMediaBytes(aggregate.totalBytes), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.Default.ChevronRight, contentDescription = "Open ${aggregate.category}")
                            }
                        }
                    }
                }
            }
        } else {
            val categoryItems = remember(scopedMedia, selectedSubcategory) {
                scopedMedia.filter { selectedSubcategory == null || it.sourceSubcategory == selectedSubcategory }
                    .sortedWith(compareBy<VideoItem> { it.sourceSubcategory ?: "" }.thenByDescending { it.dateModified ?: 0L })
            }

            if (subcategories.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    subcategories.forEach { subcategory ->
                        FilterChip(
                            selected = selectedSubcategory == subcategory.subcategory,
                            onClick = {
                                selectedSubcategory = if (selectedSubcategory == subcategory.subcategory) null else subcategory.subcategory
                            },
                            label = { Text("${subcategory.subcategory} • ${subcategory.itemCount} • ${formatMediaBytes(subcategory.totalBytes)}") },
                            leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.testTag("source_subcategory_${subcategory.subcategory}")
                        )
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                items(categoryItems, key = { it.id }) { media ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("source_media_${media.id}"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (media.mimeType?.startsWith("image/") == true) Icons.Default.Image else Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(9.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(media.displayName, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(formatMediaBytes(media.sizeBytes ?: 0L), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    media.sourceSubcategory?.let { subcategory ->
                                        val evidence = if (media.subcategoryEvidenceKind == "filename_token") "Inferred from filename" else media.subcategoryEvidenceKind.orEmpty()
                                        Text(
                                            "$subcategory • $evidence",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("source_evidence_${media.id}")
                                        )
                                    }
                                }
                            }
                            DecisionSelector(
                                media.reviewDecision,
                                modifier = Modifier.testTag("review_decision_${media.id}")
                            ) { onDecision(media.id, it) }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun InventoryMetric(title: String, count: Long, supporting: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        if (inline) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(supporting, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(count.toString(), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Text(title, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(count.toString(), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(supporting, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToolHead(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionHint("Back") { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun RulesScreen(
    rules: List<MediaRuleCard>,
    message: String?,
    onBack: () -> Unit,
    onSave: (MediaRule?, String, String, Double?) -> Unit,
    onEnable: (MediaRule, Boolean) -> Unit,
    onPreview: (MediaRule) -> Unit,
    onRun: (MediaRule) -> Unit,
    onDelete: (MediaRule) -> Unit
) {
    var editing by remember { mutableStateOf<MediaRuleCard?>(null) }
    var newKind by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().testTag("rules_screen")) {
        ToolHead("Rules", onBack) {
            ActionHint("Add copy rule") { IconButton(onClick = { newKind = "exact_duplicate" }, modifier = Modifier.testTag("add_copy_rule")) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Add copy rule")
            } }
            ActionHint("Add obscured rule") { IconButton(onClick = { newKind = "obscured" }, modifier = Modifier.testTag("add_obscured_rule")) {
                Icon(Icons.Default.BlurOn, contentDescription = "Add obscured rule")
            } }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        Text("Copies matches file name and size. Contents may differ; review before deleting", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No rules") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.rule.id }) { card ->
                    val rule = card.rule
                    Card(Modifier.fillMaxWidth().testTag("rule_${rule.id}")) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (rule.kind == "obscured") "Obscure" else "Copies", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = rule.enabled, onCheckedChange = { onEnable(rule, it) })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                ActionHint("Preview") { IconButton(onClick = { onPreview(rule) }) { Icon(Icons.Default.Visibility, contentDescription = "Preview") } }
                                ActionHint("Run") { IconButton(onClick = { onRun(rule) }, enabled = rule.enabled) { Icon(Icons.Default.PlayArrow, contentDescription = "Run") } }
                                ActionHint("Edit") { IconButton(onClick = { editing = card }) { Icon(Icons.Default.Edit, contentDescription = "Edit") } }
                                ActionHint("Delete") { IconButton(onClick = { onDelete(rule) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") } }
                            }
                        }
                    }
                }
            }
        }
    }
    val dialogRule = editing
    val dialogKind = dialogRule?.rule?.kind ?: newKind
    if (dialogKind != null) {
        RuleDialog(
            rule = dialogRule?.rule,
            definitionJson = dialogRule?.definitionJson,
            kind = dialogKind,
            onDismiss = { editing = null; newKind = null },
            onSave = { name, threshold ->
                onSave(dialogRule?.rule, name, dialogKind, threshold)
                editing = null
                newKind = null
            }
        )
    }
}

@Composable
private fun RuleDialog(rule: MediaRule?, definitionJson: String?, kind: String, onDismiss: () -> Unit, onSave: (String, Double?) -> Unit) {
    var name by remember(rule, kind) { mutableStateOf(rule?.name ?: if (kind == "obscured") "Obscure" else "Copies") }
    val savedThreshold = remember(definitionJson) {
        definitionJson?.let { MediaRuleSchema.parse(it).getOrNull()?.predicate as? MediaPredicate.Obscured }?.threshold
    }
    var thresholdText by remember(rule, savedThreshold) { mutableStateOf(savedThreshold?.toString().orEmpty()) }
    val threshold = thresholdText.toDoubleOrNull()
    val valid = name.trim().length in 1..40 && (kind != "obscured" || threshold != null && threshold in 0.0..1.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "Add rule" else "Edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, supportingText = { Text("Name: 1–40 characters") }, isError = name.trim().length !in 1..40, singleLine = true)
                if (kind == "obscured") {
                    androidx.compose.material3.OutlinedTextField(
                        thresholdText,
                        { thresholdText = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("Score") },
                        supportingText = { Text("0–1") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = { ActionHint("Save") { IconButton(onClick = { onSave(name, threshold) }, enabled = valid) { Icon(Icons.Default.Save, contentDescription = "Save") } } },
        dismissButton = { ActionHint("Cancel") { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel") } } }
    )
}

@Composable
private fun TagsScreen(
    tags: List<CustomTag>, assignments: List<MediaTagCrossRef>, media: List<VideoItem>, message: String?,
    onBack: () -> Unit, onAdd: (String) -> Unit, onRename: (CustomTag, String) -> Unit,
    onDelete: (CustomTag) -> Unit, onAssign: (Long, Long) -> Unit, onUnassign: (Long, Long) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CustomTag?>(null) }
    var rename by remember { mutableStateOf<CustomTag?>(null) }
    var onlyTagged by remember { mutableStateOf(false) }
    fun nameError(value: String, exceptId: Long? = null): String? {
        val clean = value.trim()
        return when {
            clean.length !in 1..40 -> "Name must contain 1–40 characters"
            tags.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) } -> "Name already exists"
            else -> null
        }
    }
    val inputError = nameError(input)
    Column(Modifier.fillMaxSize().testTag("tags_screen")) {
        ToolHead("Tags", onBack)
        message?.let { Text(it, modifier = Modifier.padding(8.dp)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(input, { input = it }, label = { Text("Tag") }, supportingText = { Text(inputError ?: "Name: 1–40 characters") }, isError = inputError != null, singleLine = true, modifier = Modifier.weight(1f))
            ActionHint("Add") { IconButton(onClick = { onAdd(input); input = "" }, enabled = inputError == null) { Icon(Icons.Default.Add, contentDescription = "Add") } }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tags, key = { "tag-${it.id}" }) { tag ->
                val count = assignments.count { it.tagId == tag.id }
                Card(Modifier.fillMaxWidth().clickable { selected = if (selected?.id == tag.id) null else tag }) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tag.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(count.toString(), fontSize = 12.sp)
                        ActionHint("Edit") { IconButton(onClick = { rename = tag }) { Icon(Icons.Default.Edit, contentDescription = "Edit") } }
                        ActionHint("Delete") { IconButton(onClick = { onDelete(tag); if (selected?.id == tag.id) selected = null }) { Icon(Icons.Default.Delete, contentDescription = "Delete") } }
                    }
                }
            }
            selected?.let { tag ->
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Media", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(top = 8.dp))
                        ActionHint(if (onlyTagged) "Show all files" else "Show tagged files") { IconButton(onClick = { onlyTagged = !onlyTagged }) {
                            Icon(if (onlyTagged) Icons.Default.FilterAltOff else Icons.Default.FilterAlt, contentDescription = if (onlyTagged) "Show all" else "Tagged")
                        } }
                    }
                }
                val visibleMedia = media.filter {
                    it.isCurrentlyAvailable && !it.isPending && !it.isTrashed &&
                        (!onlyTagged || assignments.any { ref -> ref.tagId == tag.id && ref.mediaId == it.id })
                }
                items(visibleMedia, key = { "media-${tag.id}-${it.id}" }) { item ->
                    val assigned = assignments.any { it.tagId == tag.id && it.mediaId == item.id }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.displayName, Modifier.weight(1f))
                        ActionHint(if (assigned) "Remove tag from file" else "Tag this file") { IconButton(onClick = { if (assigned) onUnassign(item.id, tag.id) else onAssign(item.id, tag.id) }) {
                            Icon(if (assigned) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline, contentDescription = if (assigned) "Remove tag" else "Add tag")
                        } }
                    }
                }
            }
        }
    }
    rename?.let { tag ->
        var value by remember(tag) { mutableStateOf(tag.name) }
        AlertDialog(
            onDismissRequest = { rename = null }, title = { Text("Edit") },
            text = { androidx.compose.material3.OutlinedTextField(value, { value = it }, label = { Text("Tag") }, supportingText = { Text(nameError(value, tag.id) ?: "Name: 1–40 characters") }, isError = nameError(value, tag.id) != null, singleLine = true) },
            confirmButton = { ActionHint("Save") { IconButton(onClick = { onRename(tag, value); rename = null }, enabled = nameError(value, tag.id) == null) { Icon(Icons.Default.Save, contentDescription = "Save") } } },
            dismissButton = { ActionHint("Cancel") { IconButton(onClick = { rename = null }) { Icon(Icons.Default.Close, contentDescription = "Cancel") } } }
        )
    }
}

@Composable
private fun BinScreen(
    media: List<VideoItem>, message: String?, onBack: () -> Unit, onRecover: (Long) -> Unit,
    loadThumbnail: suspend (String) -> android.graphics.Bitmap?, onDeleteAll: () -> Unit
) {
    Column(Modifier.fillMaxSize().testTag("bin_screen")) {
        ToolHead("Bin", onBack) {
            ActionHint("Delete all") { IconButton(onClick = onDeleteAll, enabled = media.any { it.isCurrentlyAvailable && !it.isPending && !it.isTrashed }, modifier = Modifier.testTag("delete_bin")) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Delete all")
            } }
        }
        message?.let { Text(it, modifier = Modifier.padding(8.dp)) }
        Text("${media.size} • ${formatMediaBytes(media.sumOf { it.sizeBytes ?: 0L })}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        if (media.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Bin empty") }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(media, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().testTag("bin_${item.id}")) {
                        Column {
                            MediaThumb(item.uriString, item.displayName, loadThumbnail, Modifier.fillMaxWidth().aspectRatio(1f))
                            Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.displayName, Modifier.weight(1f), fontSize = 11.sp)
                                ActionHint("Recover") { IconButton(onClick = { onRecover(item.id) }, enabled = item.isCurrentlyAvailable && !item.isPending && !item.isTrashed) { Icon(Icons.Default.RestoreFromTrash, contentDescription = "Recover") } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(uri: String, description: String, loader: suspend (String) -> android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = null
        value = loader(uri)
    }
    // The renderer can retain this bitmap after disposal; let GC release its native allocation.
    if (bitmap == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.Image, contentDescription = "Preview unavailable: $description") }
    } else {
        Image(bitmap!!.asImageBitmap(), contentDescription = "Preview image: $description", modifier = modifier, contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DecisionSelector(
    current: String,
    modifier: Modifier = Modifier,
    onDecision: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        "keep" -> "Keep"
        "review_later" -> "Later"
        "ready_to_delete" -> "Remove"
        else -> "New"
    }
    Box(modifier = modifier) {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp), modifier = Modifier.height(48.dp)) {
            Text(label, fontSize = 10.sp)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "unreviewed" to "New",
                "keep" to "Keep",
                "review_later" to "Later",
                "ready_to_delete" to "Remove"
            ).forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onDecision(value)
                    }
                )
            }
        }
    }
}

private fun sourceIcon(category: String) = when (category) {
    "Camera" -> Icons.Default.PhotoCamera
    "Screenshots" -> Icons.Default.Screenshot
    "Screen recordings" -> Icons.Default.ScreenShare
    "Downloads" -> Icons.Default.Download
    "WhatsApp", "WhatsApp Business", "Messenger" -> Icons.Default.Chat
    else -> Icons.Default.Folder
}

private fun formatMediaBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (value >= 100) String.format(Locale.getDefault(), "%.0f %s", value, units[unit])
    else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

// String format helpers
private fun formatTimestamp(ms: Long): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}

private fun formatDuration(ms: Long): String {
    val secs = ms / 1000
    return if (secs == 0L) "0" else secs.toString()
}

class TextStyle(
    val fontSize: androidx.compose.ui.unit.TextUnit,
    val fontFamily: androidx.compose.ui.text.font.FontFamily? = null
) {
    fun toCompose(): androidx.compose.ui.text.TextStyle {
        return androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontFamily = fontFamily)
    }
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    textStyle: TextStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        textStyle = textStyle.toCompose()
    )
}

private fun mediaFolderLabel(media: VideoItem): String =
    media.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }
        ?: media.bucketDisplayName?.takeIf { it.isNotBlank() }
        ?: "Unknown folder"
