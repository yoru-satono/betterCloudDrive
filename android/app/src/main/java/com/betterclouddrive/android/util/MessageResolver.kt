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
        if (key == "notification.teamQuotaAlert.content") {
            return context.getString(resource, params.text("teamName"), params.text("threshold"))
        }
        if (key == "notification.teamInvite.content") {
            return context.getString(resource, params.text("teamName"))
        }
        if (key == "notification.teamInviteAccept.content" || key == "notification.teamInviteReject.content") {
            return context.getString(resource, params.text("userName"), params.text("teamName"))
        }
        if (key == "error.missingParameter" || key == "error.invalidParameter") {
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
        "error.badRequest" to R.string.error_bad_request, "error.unauthorized" to R.string.error_unauthorized,
        "error.tokenExpired" to R.string.error_token_expired, "error.forbidden" to R.string.error_forbidden,
        "error.notFound" to R.string.error_not_found, "error.fileNotFound" to R.string.error_file_not_found,
        "error.storageQuotaExceeded" to R.string.error_storage_quota, "error.uploadFailed" to R.string.error_upload,
        "error.downloadFailed" to R.string.error_download, "error.validation" to R.string.error_validation,
        "error.missingParameter" to R.string.error_missing_parameter, "error.invalidParameter" to R.string.error_invalid_parameter,
        "notification.teamInvite.title" to R.string.notification_team_invite_title,
        "notification.teamInvite.content" to R.string.notification_team_invite_content,
        "notification.teamInviteAccept.title" to R.string.notification_team_invite_accept_title,
        "notification.teamInviteAccept.content" to R.string.notification_team_invite_accept_content,
        "notification.teamInviteReject.title" to R.string.notification_team_invite_reject_title,
        "notification.teamInviteReject.content" to R.string.notification_team_invite_reject_content,
        "notification.teamQuotaAlert.title" to R.string.notification_quota_title,
        "notification.teamQuotaAlert.content" to R.string.notification_quota_content,
        "notification.teamDelete.title" to R.string.notification_team_delete_title,
        "notification.teamDelete.content" to R.string.notification_team_delete_content,
        "task.failed" to R.string.task_failed, "task.succeeded" to R.string.task_succeeded,
        "task.resultExpired" to R.string.task_result_expired,
    )
}
