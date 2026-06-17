package com.betterclouddrive.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.data.repository.FileRepository
import com.betterclouddrive.android.domain.model.FileItem
import com.betterclouddrive.android.util.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreviewRouterViewModel @Inject constructor(
    private val repository: FileRepository,
    serverConfigStore: ServerConfigStore,
) : ViewModel() {
    private val _file = MutableStateFlow<FileItem?>(null)
    val file = _file.asStateFlow()
    val serverBaseUrl = serverConfigStore.serverBaseUrlFlow

    fun load(fileId: Long) {
        viewModelScope.launch {
            when (val result = repository.getFile(fileId)) {
                is NetworkResult.Success -> _file.value = result.data
                else -> Unit
            }
        }
    }
}
