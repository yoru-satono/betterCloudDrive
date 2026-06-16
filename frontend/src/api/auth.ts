import api from './client'
import type { LoginResponse, UserEntity } from '@/types/user'
import type { ApiResponse } from '@/types/api'

export const login = (username: string, password: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })

export const sendRegistrationCode = (email: string) =>
  api.post<ApiResponse<void>>('/auth/register-code/send', { email })

export const register = (username: string, password: string, email: string, verificationCode: string) =>
  api.post<ApiResponse<void>>('/auth/register', { username, password, email, verificationCode })

export const refresh = (refreshToken: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken })

export const logout = () =>
  api.post<ApiResponse<void>>('/auth/logout')

export const getMe = () =>
  api.get<ApiResponse<UserEntity>>('/auth/me')

export const getWebDavSettings = () =>
  api.get<ApiResponse<{ enabled: boolean }>>('/auth/webdav')

export const updateWebDavSettings = (enabled: boolean, password?: string) =>
  api.put<ApiResponse<UserEntity>>('/auth/webdav', { enabled, password })

export const forgotPassword = (email: string) =>
  api.post<ApiResponse<void>>('/auth/forgot-password', { email })

export const resetPassword = (email: string, code: string, newPassword: string) =>
  api.post<ApiResponse<void>>('/auth/reset-password', { email, code, newPassword })
