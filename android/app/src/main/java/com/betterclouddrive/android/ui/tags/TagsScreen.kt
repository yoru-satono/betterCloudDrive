package com.betterclouddrive.android.ui.tags

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterclouddrive.android.domain.model.Tag
import com.betterclouddrive.android.ui.components.EmptyState
import com.betterclouddrive.android.ui.components.InputDialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.repository.TagRepository
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.ui.navigation.MainScaffold
import com.betterclouddrive.android.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagsUiState(val tags: List<Tag> = emptyList(), val isLoading: Boolean = false)

@HiltViewModel
class TagsViewModel @Inject constructor(private val repository: TagRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TagsUiState()); val uiState = _uiState.asStateFlow()
    fun load() {
        viewModelScope.launch {
            when (val r = repository.listTags()) {
                is NetworkResult.Success -> _uiState.value = _uiState.value.copy(tags = r.data)
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }
    fun create(name: String) { viewModelScope.launch { repository.createTag(name); load() } }
    fun delete(tagId: Long) { viewModelScope.launch { repository.deleteTag(tagId); load() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTagFiles: (Long) -> Unit,
    onNavigateMain: (String) -> Unit,
    viewModel: TagsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }

    MainScaffold(
        currentRoute = Screen.TAGS,
        onNavigate = onNavigateMain,
        topBar = {
            TopAppBar(
                title = { Text("标签管理") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    IconButton(
                        onClick = { showCreate = true },
                        modifier = Modifier.testTag("tags-create"),
                    ) { Icon(Icons.Default.Add, "新建") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (uiState.tags.isEmpty()) {
            EmptyState(icon = Icons.Default.Label, title = "暂无标签", subtitle = "点击右上角 + 创建", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(uiState.tags, key = { it.id }) { tag ->
                    ListItem(
                        headlineContent = { Text(tag.tagName) },
                        leadingContent = {
                            Surface(shape = MaterialTheme.shapes.small, color = try { Color(android.graphics.Color.parseColor(tag.color)) } catch (_: Exception) { MaterialTheme.colorScheme.primary }, modifier = Modifier.size(24.dp)) {}
                        },
                        modifier = Modifier.testTag("tag-row-${tag.tagName}").clickable { onNavigateToTagFiles(tag.id) },
                    )
                }
            }
        }
        if (showCreate) {
            InputDialog(title = "新建标签", placeholder = "标签名称", onDismiss = { showCreate = false }, onConfirm = { viewModel.create(it); showCreate = false })
        }
    }
}
