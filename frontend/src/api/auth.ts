import api from './client'
import type { LoginResponse, UserEntity } from '@/types/user'
import type { ApiResponse } from '@/types/api'

export const login = (username: string, password: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/login', { username, password })

export const register = (username: string, password: string, email?: string) =>
  api.post<ApiResponse<void>>('/auth/register', { username, password, email })

export const refresh = (refreshToken: string) =>
  api.post<ApiResponse<LoginResponse>>('/auth/refresh', { refreshToken })

export const logout = () =>
  api.post<ApiResponse<void>>('/auth/logout')

export const getMe = () =>
  api.get<ApiResponse<UserEntity>>('/auth/me')

export const forgotPassword = (email: string) =>
  api.post<ApiResponse<void>>('/auth/forgot-password', { email })

export const resetPassword = (email: string, code: string, newPassword: string) =>
  api.post<ApiResponse<void>>('/auth/reset-password', { email, code, newPassword })

export const sendVerificationCode = () =>
  api.post<ApiResponse<void>>('/auth/verify-email/send')

export const verifyEmail = (code: string) =>
  api.post<ApiResponse<void>>('/auth/verify-email/confirm', { code })

export const changePassword = (oldPassword: string, newPassword: string) =>
  api.post<ApiResponse<void>>('/auth/change-password', { oldPassword, newPassword })

export const updateProfile = (email: string) =>
  api.put<ApiResponse<void>>('/auth/profile', { email })
