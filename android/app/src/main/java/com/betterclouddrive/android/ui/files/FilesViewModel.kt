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
    private val uploadRepository: UploadRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun loadFiles(folderId: Long? = null) {
        viewModelScope.launch {
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

    // Upload
    fun uploadFile(uri: Uri) {
        viewModelScope.launch {
            val fileName = uploadRepository.getFileName(uri)
            val fileSize = uploadRepository.getFileSize(uri)
            if (fileSize <= 0) {
                _events.emit(UiEvent.ShowSnackbar("无法读取文件", true))
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isUploading = true,
                uploadFileName = fileName,
                uploadProgress = 0f,
            )

            val inputStream = uploadRepository.openInputStream(uri) ?: run {
                _events.emit(UiEvent.ShowSnackbar("无法打开文件", true))
                _uiState.value = _uiState.value.copy(isUploading = false)
                return@launch
            }

            try {
                // Try instant upload first
                val fullData = inputStream.readBytes()
                inputStream.close()
                val md5 = uploadRepository.computeMd5(fullData)
                val totalChunks = ((fileSize + 5_242_880 - 1) / 5_242_880).toInt() // ceil division by 5MB

                val instantResult = uploadRepository.instantUpload(
                    _uiState.value.currentFolderId ?: 0,
                    fileName,
                    fileSize,
                    md5,
                )
                if (instantResult is NetworkResult.Success) {
                    _uiState.value = _uiState.value.copy(isUploading = false, uploadProgress = 1f)
                    _events.emit(UiEvent.ShowSnackbar("秒传成功"))
                    loadFiles(_uiState.value.currentFolderId)
                    return@launch
                }

                // Fallback: chunked upload
                val initResult = uploadRepository.initUpload(
                    _uiState.value.currentFolderId ?: 0,
                    fileName,
                    fileSize,
                    totalChunks,
                    md5,
                )
                if (initResult !is NetworkResult.Success) {
                    _events.emit(UiEvent.ShowSnackbar("初始化上传失败: ${(initResult as NetworkResult.Error).message}", true))
                    _uiState.value = _uiState.value.copy(isUploading = false)
                    return@launch
                }

                val session = initResult.data
                val chunkSize = 5_242_880
                for (i in 0 until totalChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, fullData.size.toInt())
                    val chunk = fullData.copyOfRange(start, end)
                    uploadRepository.uploadChunk(session.sessionId, i, chunk)
                    _uiState.value = _uiState.value.copy(
                        uploadProgress = (i + 1).toFloat() / totalChunks,
                    )
                }

                val completeResult = uploadRepository.completeUpload(session.sessionId)
                if (completeResult is NetworkResult.Success) {
                    _events.emit(UiEvent.ShowSnackbar("上传完成"))
                    loadFiles(_uiState.value.currentFolderId)
                } else {
                    _events.emit(UiEvent.ShowSnackbar("上传完成失败: ${(completeResult as NetworkResult.Error).message}", true))
                }
            } catch (e: Exception) {
                _events.emit(UiEvent.ShowSnackbar("上传异常: ${e.message}", true))
            } finally {
                _uiState.value = _uiState.value.copy(isUploading = false)
                try { inputStream.close() } catch (_: Exception) {}
            }
        }
    }
}
