package com.statussaver.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.statussaver.app.model.StatusSource
import com.statussaver.app.ui.StatusUiEvent
import com.statussaver.app.ui.StatusViewModel
import com.statussaver.app.ui.screens.MediaViewerScreen
import com.statussaver.app.ui.screens.PermissionScreen
import com.statussaver.app.ui.screens.StatusHomeScreen
import com.statussaver.app.ui.theme.StatusSaverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StatusSaverTheme {
                val viewModel: StatusViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                var pendingSafSource by remember { mutableStateOf<StatusSource?>(null) }

                val openTreeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    val targetSource = pendingSafSource
                    if (targetSource != null) {
                        viewModel.onTreeUriGranted(targetSource, treeUri)
                        pendingSafSource = null
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is StatusUiEvent.ShowMessage -> {
                                snackbarHostState.showSnackbar(event.message)
                            }
                            is StatusUiEvent.LaunchShareIntent -> {
                                startActivity(event.chooserIntent)
                            }
                        }
                    }
                }

                val launchSafPicker: (StatusSource) -> Unit = { source ->
                    pendingSafSource = source
                    openTreeLauncher.launch(viewModel.initialUriFor(source))
                }

                val activeViewerId = uiState.activeViewerMediaId
                val viewerItems = remember(uiState.allStatuses, uiState.sourceFilter, activeViewerId) {
                    val activeItem = uiState.allStatuses.firstOrNull { it.id == activeViewerId }
                    if (activeItem == null) emptyList()
                    else if (activeItem.type == com.statussaver.app.model.StatusMediaType.IMAGE) uiState.images
                    else uiState.videos
                }

                when {
                    activeViewerId != null && viewerItems.isNotEmpty() -> {
                        MediaViewerScreen(
                            items = viewerItems,
                            initialMediaId = activeViewerId,
                            onBack = viewModel::closeViewer,
                            onSave = viewModel::saveStatus,
                            onShare = viewModel::shareStatus
                        )
                    }
                    uiState.requiresSafOnboarding -> {
                        PermissionScreen(
                            grantedSources = uiState.grantedSources,
                            installedSources = uiState.installedSources,
                            onGrantFolder = launchSafPicker,
                            onContinue = { viewModel.refreshPermissionsAndStatuses() }
                        )
                    }
                    else -> {
                        StatusHomeScreen(
                            uiState = uiState,
                            snackbarHostState = snackbarHostState,
                            onSelectFilter = viewModel::setSourceFilter,
                            onRefresh = { viewModel.refreshPermissionsAndStatuses(isPullToRefresh = true) },
                            onMediaClick = viewModel::openViewer,
                            onQuickSave = viewModel::saveStatus,
                            onGrantFolder = launchSafPicker,
                            onRevokeFolder = viewModel::revokeSource
                        )
                    }
                }
            }
        }
    }
}
