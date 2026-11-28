package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.common.util.formatExceptionForLog
import me.rerere.common.util.runCatchingCancellable
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellManager

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val backgroundShellManager: BackgroundShellManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(name: String) {
        viewModelScope.launch {
            runCatchingCancellable { repository.create(name) }
                .onFailure { Log.e("WorkspaceVM", formatExceptionForLog("Workspace.create", it)) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { repository.rename(workspace.id, name) }
                .onFailure { Log.e("WorkspaceVM", formatExceptionForLog("Workspace.rename", it)) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            backgroundShellManager.killWorkspace(workspace.id)
            repository.delete(workspace.id)
        }
    }
}
