package com.betterclouddrive.android.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.*
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.ui.components.BreadcrumbItem
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUiState(
    val files: List<FileItem> = emptyList(),
    val breadcrumb: List<BreadcrumbItem> = listOf(BreadcrumbItem(null, "根目录")),
    val isLoading: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.LIST,
    val currentFolderId: Long? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
    val selectedFile: FileItem? = null, // tablet detail pane
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val uploadFileName: String = "",
)

enum class ViewMode { LIST, GRID }

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val transferRepository: TransferRepository,
    private val favoriteRepository: FavoriteRepository,
    private val shareRepository: ShareRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun loadFiles(folderId: Long? = null) {
        viewModelScope.launch {
            loadFilesInternal(folderId)
        }
    }

    private suspend fun loadFilesInternal(folderId: Long? = null) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, currentFolderId = folderId)
        when (val result = fileRepository.listFiles(parentId = folderId)) {
            is NetworkResult.Success -> {
                val data = result.data
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    files = data.records,
                    page = data.page,
                    totalPages = data.pages,
                )
            }
            is NetworkResult.Error -> {
                _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
            }
            is NetworkResult.Loading -> {}
        }
    }

    fun navigateToFolder(folderId: Long?, folderName: String) {
        _uiState.value = _uiState.value.copy(currentFolderId = folderId)
        val breadcrumb = _uiState.value.breadcrumb.toMutableList()
        val idx = breadcrumb.indexOfFirst { it.id == folderId }
        if (idx >= 0) {
            // Navigate back to an existing breadcrumb
            _uiState.value = _uiState.value.copy(breadcrumb = breadcrumb.subList(0, idx + 1))
        } else {
            breadcrumb.add(BreadcrumbItem(folderId, folderName))
            _uiState.value = _uiState.value.copy(breadcrumb = breadcrumb)
        }
        loadFiles(folderId)
    }

    fun navigateToBreadcrumb(item: BreadcrumbItem) {
        val breadcrumb = _uiState.value.breadcrumb.toMutableList()
        val idx = breadcrumb.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            _uiState.value = _uiState.value.copy(
                breadcrumb = breadcrumb.subList(0, idx + 1),
                currentFolderId = item.id,
            )
        }
        loadFiles(item.id)
    }

    fun navigateToParent() {
        val breadcrumb = _uiState.value.breadcrumb
        if (breadcrumb.size <= 1) return
        val parent = breadcrumb[breadcrumb.size - 2]
        _uiState.value = _uiState.value.copy(
            breadcrumb = breadcrumb.dropLast(1),
            currentFolderId = parent.id,
        )
        loadFiles(parent.id)
    }

    fun createFolder(folderName: String) {
        viewModelScope.launch {
            when (val result = fileRepository.createFolder(_uiState.value.currentFolderId, folderName)) {
                is NetworkResult.Success -> loadFiles(_uiState.value.currentFolderId)
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("创建失败: ${result.message}", true))
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun renameFile(fileId: Long, newName: String) {
        viewModelScope.launch {
            when (val result = fileRepository.renameFile(fileId, newName)) {
                is NetworkResult.Success -> loadFiles(_uiState.value.currentFolderId)
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("重命名失败: ${result.message}", true))
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deleteFiles(fileIds: List<Long>) {
        viewModelScope.launch {
            when (val result = fileRepository.deleteFiles(fileIds)) {
                is NetworkResult.Success -> loadFiles(_uiState.value.currentFolderId)
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("删除失败: ${result.message}", true))
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun toggleViewMode() {
        val current = _uiState.value.viewMode
        _uiState.value = _uiState.value.copy(
            viewMode = if (current == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST,
        )
    }

    fun selectFile(file: FileItem?) {
        _uiState.value = _uiState.value.copy(selectedFile = file)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                val task = transferRepository.enqueueUpload(_uiState.value.currentFolderId, uri)
                transferRepository.start(task.id, viewModelScope) {
                    viewModelScope.launch { loadFiles(_uiState.value.currentFolderId) }
                }
            }
            _events.emit(UiEvent.ShowSnackbar("已加入传输队列"))
        }
    }

    fun enqueueDownload(file: FileItem, targetUri: Uri) {
        viewModelScope.launch {
            val task = transferRepository.enqueueDownload(file.id, file.fileName, file.fileSize, targetUri)
            _events.emit(UiEvent.ShowSnackbar("已加入传输队列"))
            transferRepository.start(task.id, viewModelScope)
        }
    }

    fun addFavorite(file: FileItem) {
        viewModelScope.launch {
            when (val result = favoriteRepository.addFavorite(file.id)) {
                is NetworkResult.Success -> _events.emit(UiEvent.ShowSnackbar("已收藏"))
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("收藏失败: ${result.message}", true))
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun openFolder(folderId: Long? = null) {
        viewModelScope.launch {
            val breadcrumb = mutableListOf(BreadcrumbItem(null, "根目录"))
            if (folderId != null) {
                val path = mutableListOf<FileItem>()
                var currentId: Long? = folderId
                while (currentId != null) {
                    when (val result = fileRepository.getFile(currentId)) {
                        is NetworkResult.Success -> {
                            val folder = result.data
                            path.add(folder)
                            currentId = folder.parentId
                        }
                        else -> {
                            currentId = null
                        }
                    }
                }
                path.asReversed().forEach { folder ->
                    breadcrumb.add(BreadcrumbItem(folder.id, folder.fileName))
                }
            }
            _uiState.value = _uiState.value.copy(
                breadcrumb = breadcrumb,
                currentFolderId = folderId,
                selectedFile = null,
            )
            loadFilesInternal(folderId)
        }
    }

    fun createShare(file: FileItem) {
        viewModelScope.launch {
            when (val result = shareRepository.createShare(file.id)) {
                is NetworkResult.Success -> _events.emit(UiEvent.ShowSnackbar("已创建分享: ${result.data.shareCode}"))
                is NetworkResult.Error -> _events.emit(UiEvent.ShowSnackbar("分享失败: ${result.message}", true))
                is NetworkResult.Loading -> {}
            }
        }
    }
}
