package com.betterclouddrive.android.util

import android.content.Context
import android.util.Log
import com.betterclouddrive.android.BuildConfig
import com.betterclouddrive.android.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object MessageResolver {
    private lateinit var context: Context

    fun initialize(applicationContext: Context) {
        context = applicationContext.applicationContext
    }

    fun resolve(key: String?, params: Map<String, JsonElement> = emptyMap()): String {
        val resource = resources[key]
        if (resource == null) {
            Log.w("MessageResolver", "Unknown server message key: $key")
            return if (BuildConfig.DEBUG && !key.isNullOrBlank()) context.getString(R.string.error_unknown_debug, key)
            else context.getString(R.string.error_unknown)
        }
        if (key == "notification.team_quota_alert.content") {
            return context.getString(resource, params.text("teamName"), params.text("threshold"))
        }
        if (key == "notification.team_invite.content") {
            return context.getString(resource, params.text("teamName"))
        }
        if (key == "notification.team_invite_accept.content" || key == "notification.team_invite_reject.content") {
            return context.getString(resource, params.text("userName"), params.text("teamName"))
        }
        if (key == "error.missing_parameter" || key == "error.invalid_parameter") {
            return context.getString(resource, params.text("name"))
        }
        if (key == "error.validation") return context.getString(resource, params.text("fields"))
        return context.getString(resource)
    }

    private fun Map<String, JsonElement>.text(name: String): String =
        (get(name) as? JsonPrimitive)?.contentOrNull ?: ""

    private val resources = mapOf(
        "common.success" to R.string.common_success,
        "error.internal" to R.string.error_internal, "error.network" to R.string.error_network,
        "error.bad_request" to R.string.error_bad_request, "error.unauthorized" to R.string.error_unauthorized,
        "error.token_expired" to R.string.error_token_expired, "error.forbidden" to R.string.error_forbidden,
        "error.not_found" to R.string.error_not_found, "error.file_not_found" to R.string.error_file_not_found,
        "error.storage_quota_exceeded" to R.string.error_storage_quota, "error.upload_failed" to R.string.error_upload,
        "error.download_failed" to R.string.error_download, "error.validation" to R.string.error_validation,
        "error.missing_parameter" to R.string.error_missing_parameter, "error.invalid_parameter" to R.string.error_invalid_parameter,
        "notification.team_invite.title" to R.string.notification_team_invite_title,
        "notification.team_invite.content" to R.string.notification_team_invite_content,
        "notification.team_invite_accept.title" to R.string.notification_team_invite_accept_title,
        "notification.team_invite_accept.content" to R.string.notification_team_invite_accept_content,
        "notification.team_invite_reject.title" to R.string.notification_team_invite_reject_title,
        "notification.team_invite_reject.content" to R.string.notification_team_invite_reject_content,
        "notification.team_quota_alert.title" to R.string.notification_quota_title,
        "notification.team_quota_alert.content" to R.string.notification_quota_content,
        "notification.team_delete.title" to R.string.notification_team_delete_title,
        "notification.team_delete.content" to R.string.notification_team_delete_content,
        "task.failed" to R.string.task_failed, "task.succeeded" to R.string.task_succeeded,
        "task.result_expired" to R.string.task_result_expired,
    )
}
