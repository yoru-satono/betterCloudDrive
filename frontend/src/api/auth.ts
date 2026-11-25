import api from './client'
import type { LoginResponse, RegistrationResponse, UserEntity } from '@/types/user'
import type { ApiResponse } from '@/types/api'
import type { SupportedLocale } from '@/i18n'

export const login = (username: string, password: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })

export const sendRegistrationCode = (email: string, preferredLocale?: SupportedLocale) =>
  api.post<ApiResponse<void>>('/auth/register-code/send', { email, ...(preferredLocale ? { preferredLocale } : {}) })

export const register = (username: string, password: string, email: string, verificationCode: string, preferredLocale?: SupportedLocale) =>
  api.post<ApiResponse<RegistrationResponse>>('/auth/register', {
    username, password, email, verificationCode, ...(preferredLocale ? { preferredLocale } : {})
  })

export const refresh = (refreshToken: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken })

export const logout = () =>
  api.post<ApiResponse<void>>('/auth/logout')

export const getMe = (suppressToast = false) =>
  api.get<ApiResponse<UserEntity>>('/auth/me', { suppressToast })

export const getWebDavSettings = () =>
  api.get<ApiResponse<{ enabled: boolean }>>('/auth/webdav')

export const updateWebDavSettings = (enabled: boolean, password?: string) =>
  api.put<ApiResponse<UserEntity>>('/auth/webdav', { enabled, password })

export const updateLocale = (preferredLocale: SupportedLocale) =>
  api.put<ApiResponse<UserEntity>>('/auth/preferences/locale', { preferredLocale })

export const forgotPassword = (email: string) =>
  api.post<ApiResponse<void>>('/auth/forgot-password', { email })

export const resetPassword = (email: string, code: string, newPassword: string) =>
  api.post<ApiResponse<void>>('/auth/reset-password', { email, code, newPassword })
