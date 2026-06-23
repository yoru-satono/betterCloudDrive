package com.betterclouddrive.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterclouddrive.android.data.local.ServerConfigStore
import com.betterclouddrive.android.data.repository.AuthRepository
import com.betterclouddrive.android.domain.model.User
import com.betterclouddrive.android.util.NetworkResult
import com.betterclouddrive.android.util.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val user: User? = null,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val serverConfigStore: ServerConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    val serverBaseUrl = serverConfigStore.serverBaseUrlFlow

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.login(username, password)) {
                is NetworkResult.Success -> {
                    authRepository.saveTokens(result.data.accessToken, result.data.refreshToken)
                    fetchUser()
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is NetworkResult.Loading -> { }
            }
        }
    }

    fun register(username: String, password: String, email: String, verificationCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.register(username, password, email, verificationCode)) {
                is NetworkResult.Success -> {
                    _events.emit(UiEvent.ShowSnackbar("注册成功，请登录"))
                    _events.emit(UiEvent.NavigateBack)
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is NetworkResult.Loading -> { }
            }
        }
    }

    fun sendRegisterCode(email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = authRepository.sendRegisterCode(email)) {
                is NetworkResult.Success -> _events.emit(UiEvent.ShowSnackbar("验证码已发送到邮箱"))
                is NetworkResult.Error -> _uiState.value = _uiState.value.copy(error = result.message)
                is NetworkResult.Loading -> { }
            }
        }
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.forgotPassword(email)) {
                is NetworkResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("验证码已发送到邮箱"))
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is NetworkResult.Loading -> { }
            }
        }
    }

    fun resetPassword(email: String, code: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = authRepository.resetPassword(email, code, newPassword)) {
                is NetworkResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _events.emit(UiEvent.ShowSnackbar("密码重置成功，请登录"))
                    _events.emit(UiEvent.NavigateBack)
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
                is NetworkResult.Loading -> { }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            authRepository.clearTokens()
            _uiState.value = AuthUiState()
        }
    }

    fun saveServerBaseUrl(input: String) {
        viewModelScope.launch {
            serverConfigStore.saveServerBaseUrl(input)
                .onSuccess {
                    authRepository.clearTokens()
                    _uiState.value = AuthUiState()
                    _events.emit(UiEvent.ShowSnackbar("服务器地址已保存，请重新登录"))
                }
                .onFailure { error ->
                    _events.emit(UiEvent.ShowSnackbar(error.message ?: "服务器地址无效", true))
                }
        }
    }

    fun tryAutoLogin(onSuccess: () -> Unit) {
        viewModelScope.launch {
            // Check if we have tokens and they're valid
            fetchUser()
            if (_uiState.value.user != null) onSuccess()
        }
    }

    private suspend fun fetchUser() {
        when (val result = authRepository.getMe()) {
            is NetworkResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isLoggedIn = true,
                    user = result.data,
                )
            }
            is NetworkResult.Error -> {
                _uiState.value = _uiState.value.copy(isLoading = false, error = null)
            }
            is NetworkResult.Loading -> { }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
