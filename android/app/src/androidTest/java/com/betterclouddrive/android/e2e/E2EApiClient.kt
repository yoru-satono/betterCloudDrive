package com.betterclouddrive.android.e2e

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class E2EUser(
    val userId: Long,
    val username: String,
    val password: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
)

data class E2EFile(
    val id: Long,
    val fileName: String,
    val fileType: String,
    val parentId: Long?,
    val fileSize: Long,
    val md5Hash: String? = null,
    val versionCount: Int = 1,
) {
    val isFolder: Boolean get() = fileType == "folder"
}

data class E2EOfficeConfig(
    val editable: Boolean,
    val mode: String?,
    val callbackUrl: String?,
    val documentServerUrl: String,
    val documentKey: String?,
)

data class E2EFileVersion(
    val id: Long,
    val versionNumber: Int,
)

data class E2EShare(
    val id: Long,
    val shareCode: String,
    val fileId: Long,
    val hasPassword: Boolean,
)

data class E2ETag(
    val id: Long,
    val tagName: String,
    val color: String,
)

data class E2ETeamInvite(
    val id: Long,
    val teamId: Long,
    val email: String,
    val role: String,
    val status: String,
)

data class E2ETeamAuditLog(
    val id: Long,
    val action: String,
    val targetType: String?,
    val targetId: Long?,
)

data class E2ETeamTask(
    val id: Long,
    val taskType: String,
    val status: String,
)

data class E2ENotification(
    val id: Long,
    val teamId: Long?,
    val type: String,
    val titleKey: String,
    val contentKey: String,
    val messageParams: JSONObject,
    val link: String?,
    val isRead: Boolean,
)

data class E2ETeam(
    val id: Long,
    val name: String,
    val role: String,
)

class E2EApiClient(
    private val baseUrl: String = E2EConfig.apiBaseUrl,
    private val mailpitUrl: String = E2EConfig.mailpitApiBaseUrl,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun waitForBackend(timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val response = client.newCall(
                    Request.Builder().url("$baseUrl/files").get().build(),
                ).execute()
                response.close()
                if (response.code == 401 || response.code == 403 || response.isSuccessful) return
            } catch (error: Throwable) {
                lastError = error
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("Backend is not reachable at $baseUrl", lastError)
    }

    fun createUser(): E2EUser {
        val suffix = uniqueSuffix()
        val username = "e2e_$suffix"
        val password = "TestPass123"
        val email = "$username@test.local"
        sendRegisterCode(email)
        val code = latestVerificationCode(email)
        val registerBody = postJson(
            "$baseUrl/auth/register",
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("email", email)
                .put("verificationCode", code),
        )
        val userId = registerBody.getJSONObject("data").getLong("userId")
        val login = login(username, password)
        return E2EUser(
            userId = userId,
            username = username,
            password = password,
            email = email,
            accessToken = login.first,
            refreshToken = login.second,
        )
    }

    fun login(username: String, password: String): Pair<String, String> {
        val body = postJson(
            "$baseUrl/auth/login",
            JSONObject().put("username", username).put("password", password),
        )
        val data = body.getJSONObject("data")
        return data.getString("accessToken") to data.getString("refreshToken")
    }

    fun createFolder(token: String, folderName: String, parentId: Long? = null): E2EFile {
        val requestBody = JSONObject()
            .put("folderName", folderName)
            .put("parentId", parentId)
        return parseFile(postJson("$baseUrl/files/folder", requestBody, token).getJSONObject("data"))
    }

    fun createTeam(token: String, name: String): E2ETeam {
        return parseTeam(postJson("$baseUrl/teams", JSONObject().put("name", name), token).getJSONObject("data"))
    }

    fun addTeamMember(token: String, teamId: Long, username: String, role: String = "VIEWER") {
        postJson("$baseUrl/teams/$teamId/members", JSONObject().put("username", username).put("role", role), token)
    }

    fun upsertTeamFileRolePermission(
        token: String,
        teamId: Long,
        fileId: Long,
        role: String,
        canRead: Boolean = true,
        canWrite: Boolean = false,
        canShare: Boolean = false,
        canDelete: Boolean = false,
        canManage: Boolean = false,
    ) {
        upsertTeamFilePermission(
            token = token,
            teamId = teamId,
            fileId = fileId,
            subjectType = "ROLE",
            subjectUserId = null,
            subjectRole = role,
            canRead = canRead,
            canWrite = canWrite,
            canShare = canShare,
            canDelete = canDelete,
            canManage = canManage,
        )
    }

    fun upsertTeamFileUserPermission(
        token: String,
        teamId: Long,
        fileId: Long,
        userId: Long,
        canRead: Boolean = true,
        canWrite: Boolean = false,
        canShare: Boolean = false,
        canDelete: Boolean = false,
        canManage: Boolean = false,
    ) {
        upsertTeamFilePermission(
            token = token,
            teamId = teamId,
            fileId = fileId,
            subjectType = "USER",
            subjectUserId = userId,
            subjectRole = null,
            canRead = canRead,
            canWrite = canWrite,
            canShare = canShare,
            canDelete = canDelete,
            canManage = canManage,
        )
    }

    fun createTeamInvite(token: String, teamId: Long, email: String, role: String = "VIEWER"): E2ETeamInvite {
        return parseTeamInvite(
            postJson("$baseUrl/teams/$teamId/invites", JSONObject().put("email", email).put("role", role), token)
                .getJSONObject("data"),
        )
    }

    fun listTeamInvites(token: String, teamId: Long): List<E2ETeamInvite> {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/invites")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseTeamInvite(records.getJSONObject(index)) }
    }

    fun resendTeamInvite(token: String, teamId: Long, inviteId: Long): E2ETeamInvite {
        return parseTeamInvite(
            postJson("$baseUrl/teams/$teamId/invites/$inviteId/resend", JSONObject(), token).getJSONObject("data"),
        )
    }

    fun cancelTeamInvite(token: String, teamId: Long, inviteId: Long) {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/invites/$inviteId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    fun latestTeamInviteToken(email: String): String {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val text = latestMailText(email, "team-invites/")
            val match = Regex("""team-invites/([A-Za-z0-9]+)""").find(text)
            if (match != null) return match.groupValues[1]
            Thread.sleep(500)
        }
        throw AssertionError("No team invite token received for $email")
    }

    fun acceptTeamInvite(token: String, accessToken: String) {
        val request = Request.Builder()
            .url("$baseUrl/team-invites/$token/accept")
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $accessToken")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    fun listTeamAuditLogs(token: String, teamId: Long): List<E2ETeamAuditLog> {
        val url = "$baseUrl/teams/$teamId/audit-logs".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseTeamAuditLog(records.getJSONObject(index)) }
    }

    fun queueTeamAuditExport(token: String, teamId: Long): E2ETeamTask =
        parseTeamTask(postJson("$baseUrl/teams/$teamId/audit-logs/export-tasks", JSONObject(), token).getJSONObject("data"))

    fun queueTeamFolderZip(token: String, teamId: Long, folderId: Long): E2ETeamTask =
        parseTeamTask(postJson("$baseUrl/teams/$teamId/download/folders/$folderId/zip/tasks", JSONObject(), token).getJSONObject("data"))

    fun listTeamTasks(token: String, teamId: Long): List<E2ETeamTask> {
        val request = Request.Builder().url("$baseUrl/teams/$teamId/tasks?page=1&size=100").get()
            .header("Authorization", "Bearer $token").build()
        val records = expectApiSuccess(client.newCall(request).execute()).getJSONObject("data").getJSONArray("records")
        return (0 until records.length()).map { parseTeamTask(records.getJSONObject(it)) }
    }

    fun listNotifications(token: String, unreadOnly: Boolean = false): List<E2ENotification> {
        val url = "$baseUrl/notifications".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .addQueryParameter("unreadOnly", unreadOnly.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseNotification(records.getJSONObject(index)) }
    }

    fun createTeamFolder(token: String, teamId: Long, folderName: String, parentId: Long? = null): E2EFile {
        val requestBody = JSONObject()
            .put("folderName", folderName)
            .put("parentId", parentId)
        return parseFile(postJson("$baseUrl/teams/$teamId/files/folder", requestBody, token).getJSONObject("data"))
    }

    fun uploadTextFile(
        token: String,
        parentId: Long?,
        fileName: String,
        content: String = "android e2e sample\n",
    ): E2EFile {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val md5 = md5(bytes)
        val totalChunks = if (bytes.isEmpty()) 0 else 1
        val initBody = postJson(
            "$baseUrl/upload/init",
            JSONObject()
                .put("parentId", parentId)
                .put("fileName", fileName)
                .put("fileSize", bytes.size)
                .put("md5Hash", md5)
                .put("totalChunks", totalChunks),
            token,
        )
        val sessionId = initBody.getJSONObject("data").getString("sessionId")
        if (totalChunks > 0) {
            uploadChunk(token, sessionId, fileName, bytes)
        }
        val complete = postJson("$baseUrl/upload/$sessionId/complete", JSONObject(), token)
        val fileId = complete.getJSONObject("data").getLong("fileId")
        return getFile(token, fileId)
    }

    fun uploadTeamTextFile(
        token: String,
        teamId: Long,
        parentId: Long?,
        fileName: String,
        content: String = "android team e2e sample\n",
    ): E2EFile {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return uploadTeamFile(token, teamId, parentId, fileName, bytes, "text/plain")
    }

    fun uploadTeamDocxFile(
        token: String,
        teamId: Long,
        parentId: Long?,
        fileName: String,
        text: String = "BetterCloudDrive Android OnlyOffice E2E",
    ): E2EFile {
        return uploadTeamFile(
            token = token,
            teamId = teamId,
            parentId = parentId,
            fileName = fileName,
            bytes = minimalDocxBytes(text),
            mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
    }

    fun uploadTeamFile(
        token: String,
        teamId: Long,
        parentId: Long?,
        fileName: String,
        bytes: ByteArray,
        mediaType: String,
    ): E2EFile {
        val md5 = md5(bytes)
        val totalChunks = if (bytes.isEmpty()) 0 else 1
        val initBody = postJson(
            "$baseUrl/teams/$teamId/upload/init",
            JSONObject()
                .put("parentId", parentId)
                .put("fileName", fileName)
                .put("fileSize", bytes.size)
                .put("md5Hash", md5)
                .put("totalChunks", totalChunks),
            token,
        )
        val sessionId = initBody.getJSONObject("data").getString("sessionId")
        if (totalChunks > 0) {
            uploadTeamChunk(token, teamId, sessionId, fileName, bytes, mediaType)
        }
        val complete = postJson("$baseUrl/teams/$teamId/upload/$sessionId/complete", JSONObject(), token)
        val fileId = complete.getJSONObject("data").getLong("fileId")
        return getTeamFile(token, teamId, fileId)
    }

    fun uploadEmptyFile(token: String, parentId: Long?, fileName: String): E2EFile {
        return uploadTextFile(token, parentId, fileName, "")
    }

    fun addFavorite(token: String, fileId: Long) {
        postJson("$baseUrl/favorites/$fileId", JSONObject(), token)
    }

    fun addTeamFavorite(token: String, teamId: Long, fileId: Long) {
        postJson("$baseUrl/teams/$teamId/favorites/$fileId", JSONObject(), token)
    }

    fun getTeamFavoriteStatus(token: String, teamId: Long, fileId: Long): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/favorites/$fileId/status")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        return expectApiSuccess(client.newCall(request).execute()).getBoolean("data")
    }

    fun listTeamFavorites(token: String, teamId: Long): List<E2EFile> {
        val url = "$baseUrl/teams/$teamId/favorites".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseFile(records.getJSONObject(index)) }
    }

    fun createShare(token: String, fileId: Long, password: String? = null): E2EShare {
        val payload = JSONObject().put("fileId", fileId)
        if (password != null) payload.put("password", password)
        return parseShare(postJson("$baseUrl/shares", payload, token).getJSONObject("data"))
    }

    fun createTeamShare(token: String, teamId: Long, fileId: Long, password: String? = null): E2EShare {
        val payload = JSONObject().put("fileId", fileId)
        if (password != null) payload.put("password", password)
        return parseShare(postJson("$baseUrl/teams/$teamId/shares", payload, token).getJSONObject("data"))
    }

    fun listSharedFiles(shareCode: String, password: String? = null, parentId: Long? = null): JSONObject {
        val url = "$baseUrl/shares/access/$shareCode/files".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .apply {
                if (parentId != null) addQueryParameter("parentId", parentId.toString())
                if (password != null) addQueryParameter("password", password)
            }
            .build()
        val request = Request.Builder().url(url).get().build()
        return expectHttpJson(client.newCall(request).execute())
    }

    fun listFiles(token: String, parentId: Long? = null): List<E2EFile> {
        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .apply {
                if (parentId != null) addQueryParameter("parentId", parentId.toString())
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseFile(records.getJSONObject(index)) }
    }

    fun moveFile(token: String, fileId: Long, targetParentId: Long?) {
        postJson("$baseUrl/files/$fileId/move", targetParentBody(targetParentId), token)
    }

    fun copyFile(token: String, fileId: Long, targetParentId: Long?) {
        postJson("$baseUrl/files/$fileId/copy", targetParentBody(targetParentId), token)
    }

    fun deleteFiles(token: String, fileIds: List<Long>) {
        val request = Request.Builder()
            .url("$baseUrl/files")
            .delete(JSONObject().put("fileIds", JSONArray(fileIds)).toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $token")
            .build()
        expectOk(client.newCall(request).execute()).close()
    }

    fun deleteTeamFiles(token: String, teamId: Long, fileIds: List<Long>) {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/files")
            .delete(JSONObject().put("fileIds", JSONArray(fileIds)).toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $token")
            .build()
        expectOk(client.newCall(request).execute()).close()
    }

    fun restoreFile(token: String, fileId: Long) {
        postJson("$baseUrl/recycle-bin/$fileId/restore", JSONObject(), token)
    }

    fun restoreTeamFile(token: String, teamId: Long, fileId: Long) {
        postJson("$baseUrl/teams/$teamId/recycle-bin/$fileId/restore", JSONObject(), token)
    }

    fun permanentDeleteFile(token: String, fileId: Long) {
        val request = Request.Builder()
            .url("$baseUrl/recycle-bin/$fileId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    fun listTeamRecycleBin(token: String, teamId: Long): List<E2EFile> {
        val url = "$baseUrl/teams/$teamId/recycle-bin".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseFile(records.getJSONObject(index)) }
    }

    fun emptyRecycleBin(token: String) {
        val request = Request.Builder()
            .url("$baseUrl/recycle-bin")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    fun createTag(token: String, tagName: String, color: String = "#1890ff"): E2ETag {
        return parseTag(
            postJson("$baseUrl/tags", JSONObject().put("tagName", tagName).put("color", color), token)
                .getJSONObject("data"),
        )
    }

    fun tagFile(token: String, tagId: Long, fileId: Long) {
        postJson("$baseUrl/tags/$tagId/files", JSONObject().put("fileIds", JSONArray(listOf(fileId))), token)
    }

    fun createTeamTag(token: String, teamId: Long, tagName: String, color: String = "#1890ff"): E2ETag {
        return parseTag(
            postJson("$baseUrl/teams/$teamId/tags", JSONObject().put("tagName", tagName).put("color", color), token)
                .getJSONObject("data"),
        )
    }

    fun listTeamTags(token: String, teamId: Long): List<E2ETag> {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/tags")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute()).getJSONArray("data")
        return (0 until records.length()).map { index -> parseTag(records.getJSONObject(index)) }
    }

    fun tagTeamFile(token: String, teamId: Long, tagId: Long, fileId: Long) {
        postJson("$baseUrl/teams/$teamId/tags/$tagId/files", JSONObject().put("fileIds", JSONArray(listOf(fileId))), token)
    }

    fun listTeamFilesByTag(token: String, teamId: Long, tagId: Long): List<E2EFile> {
        val url = "$baseUrl/teams/$teamId/tags/$tagId/files".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("size", "100")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute())
            .getJSONObject("data")
            .getJSONArray("records")
        return (0 until records.length()).map { index -> parseFile(records.getJSONObject(index)) }
    }

    fun cancelShare(token: String, shareId: Long) {
        val request = Request.Builder()
            .url("$baseUrl/shares/$shareId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    fun getFile(token: String, fileId: Long): E2EFile {
        val request = Request.Builder()
            .url("$baseUrl/files/$fileId")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        return parseFile(expectApiSuccess(client.newCall(request).execute()).getJSONObject("data"))
    }

    fun getTeamFile(token: String, teamId: Long, fileId: Long): E2EFile {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/files/$fileId")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        return parseFile(expectApiSuccess(client.newCall(request).execute()).getJSONObject("data"))
    }

    fun getTeamOfficeConfig(token: String, teamId: Long, fileId: Long): E2EOfficeConfig {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/office/files/$fileId/config")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val data = expectApiSuccess(client.newCall(request).execute()).getJSONObject("data")
        val config = data.getJSONObject("config")
        val editorConfig = config.getJSONObject("editorConfig")
        val document = config.getJSONObject("document")
        return E2EOfficeConfig(
            editable = data.getBoolean("editable"),
            mode = editorConfig.optString("mode").takeIf { it.isNotBlank() },
            callbackUrl = editorConfig.optString("callbackUrl").takeIf { it.isNotBlank() },
            documentServerUrl = data.getString("documentServerUrl"),
            documentKey = document.optString("key").takeIf { it.isNotBlank() },
        )
    }

    fun forceSaveOnlyOfficeDocument(config: E2EOfficeConfig, timeoutMs: Long = 60_000) {
        val key = config.documentKey ?: throw AssertionError("OnlyOffice document key is missing")
        val payload = JSONObject()
            .put("c", "forcesave")
            .put("key", key)
        val payloadJson = payload.toString()
        val token = signOnlyOfficeJwt(payloadJson)
        val body = JSONObject(payloadJson).put("token", token)
        val documentServerUrl = config.documentServerUrl.trimEnd('/')
        val endpoints = listOfNotNull(
            "$documentServerUrl/command",
            "$documentServerUrl/coauthoring/CommandService.ashx",
            E2EConfig.onlyOfficeBaseUrl?.let { "$it/command" },
            E2EConfig.onlyOfficeBaseUrl?.let { "$it/coauthoring/CommandService.ashx" },
        ).distinct()
        var lastBody: String? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (endpoint in endpoints) {
                val response = runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(endpoint)
                            .post(body.toString().toRequestBody(jsonMediaType))
                            .header("Authorization", "Bearer $token")
                            .build(),
                    ).execute()
                }.getOrNull() ?: continue
                response.use {
                    lastBody = it.body?.string().orEmpty()
                    if (!it.isSuccessful) return@use
                    val json = JSONObject(lastBody.orEmpty())
                    if (json.optInt("error", -1) == 0) return
                }
            }
            Thread.sleep(2_000)
        }
        throw AssertionError("OnlyOffice forcesave failed: $lastBody")
    }

    fun listTeamVersions(token: String, teamId: Long, fileId: Long): List<E2EFileVersion> {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/files/$fileId/versions")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        val records = expectApiSuccess(client.newCall(request).execute()).getJSONArray("data")
        return (0 until records.length()).map { index ->
            val json = records.getJSONObject(index)
            E2EFileVersion(
                id = json.getLong("id"),
                versionNumber = json.getInt("versionNumber"),
            )
        }
    }

    fun downloadTeamFileBytes(token: String, teamId: Long, fileId: Long): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/download/$fileId")
            .get()
            .header("Authorization", "Bearer $token")
            .build()
        return expectOk(client.newCall(request).execute()).use { response ->
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    fun zipContainsText(bytes: ByteArray, text: String): Boolean {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    val raw = output.toString(Charsets.UTF_8.name())
                    if (raw.contains(text)) return true
                    if (entry.name.endsWith(".xml")) {
                        val xmlText = raw.xmlTextContent()
                        if (xmlText.contains(text)) return true
                        if (xmlText.normalizedOfficeText().contains(text.normalizedOfficeText())) return true
                    }
                }
                entry = zip.nextEntry
            }
        }
        return false
    }

    private fun String.xmlTextContent(): String {
        return replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun String.normalizedOfficeText(): String {
        return filter { it.isLetterOrDigit() }
    }

    private fun sendRegisterCode(email: String) {
        postJson("$baseUrl/auth/register-code/send", JSONObject().put("email", email))
    }

    private fun latestVerificationCode(email: String): String {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val text = latestMailText(email)
            val match = Regex("\\b\\d{6}\\b").find(text)
            if (match != null) return match.value
            Thread.sleep(500)
        }
        throw AssertionError("No verification code received for $email")
    }

    private fun latestMailText(email: String, requiredText: String? = null): String {
        val listRequest = Request.Builder().url("$mailpitUrl/messages").get().build()
        val listResponse = client.newCall(listRequest).execute()
        if (!listResponse.isSuccessful) return ""
        val list = JSONObject(listResponse.body?.string().orEmpty())
        val messages = list.optJSONArray("messages") ?: list.optJSONArray("Messages") ?: JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            if (!message.toString().contains(email)) continue
            val id = message.optString("ID", message.optString("id"))
            if (id.isBlank()) continue
            val detailRequest = Request.Builder().url("$mailpitUrl/message/$id").get().build()
            val detailResponse = client.newCall(detailRequest).execute()
            if (!detailResponse.isSuccessful) continue
            val text = detailResponse.body?.string().orEmpty()
            if (requiredText == null || text.contains(requiredText)) return text
        }
        return ""
    }

    private fun uploadChunk(token: String, sessionId: String, fileName: String, bytes: ByteArray) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody("text/plain".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/upload/$sessionId/chunk?chunkNumber=0")
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    private fun uploadTeamChunk(
        token: String,
        teamId: Long,
        sessionId: String,
        fileName: String,
        bytes: ByteArray,
        mediaType: String = "text/plain",
    ) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mediaType.toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url("$baseUrl/teams/$teamId/upload/$sessionId/chunk?chunkNumber=0")
            .post(requestBody)
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
    }

    private fun postJson(url: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return expectApiSuccess(client.newCall(builder.build()).execute())
    }

    private fun putJson(url: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .put(body.toString().toRequestBody(jsonMediaType))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return expectApiSuccess(client.newCall(builder.build()).execute())
    }

    private fun upsertTeamFilePermission(
        token: String,
        teamId: Long,
        fileId: Long,
        subjectType: String,
        subjectUserId: Long?,
        subjectRole: String?,
        canRead: Boolean,
        canWrite: Boolean,
        canShare: Boolean,
        canDelete: Boolean,
        canManage: Boolean,
    ) {
        val body = JSONObject()
            .put("subjectType", subjectType)
            .put("canRead", canRead)
            .put("canWrite", canWrite)
            .put("canShare", canShare)
            .put("canDelete", canDelete)
            .put("canManage", canManage)
        if (subjectUserId != null) body.put("subjectUserId", subjectUserId)
        if (subjectRole != null) body.put("subjectRole", subjectRole)
        putJson("$baseUrl/teams/$teamId/files/$fileId/permissions", body, token)
    }

    private fun expectHttpJson(response: okhttp3.Response): JSONObject {
        response.use {
            val body = it.body?.string().orEmpty()
            if (body.isBlank()) throw AssertionError("HTTP ${it.code}: empty body")
            return JSONObject(body)
        }
    }

    private fun expectApiSuccess(response: okhttp3.Response): JSONObject {
        expectOk(response).use {
            val json = JSONObject(it.body?.string().orEmpty())
            val code = json.optInt("code", -1)
            if (code != 200) throw AssertionError("API failed: code=$code body=$json")
            return json
        }
    }

    private fun expectOk(response: okhttp3.Response): okhttp3.Response {
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            response.close()
            throw AssertionError("HTTP ${response.code}: $body")
        }
        return response
    }

    private fun parseFile(json: JSONObject): E2EFile {
        return E2EFile(
            id = json.getLong("id"),
            fileName = json.getString("fileName"),
            fileType = json.optString("fileType", "file"),
            parentId = if (json.isNull("parentId")) null else json.getLong("parentId"),
            fileSize = json.optLong("fileSize", 0),
            md5Hash = json.optString("md5Hash").takeIf { it.isNotBlank() },
            versionCount = json.optInt("versionCount", 1),
        )
    }

    private fun parseShare(json: JSONObject): E2EShare {
        return E2EShare(
            id = json.getLong("id"),
            shareCode = json.getString("shareCode"),
            fileId = json.getLong("fileId"),
            hasPassword = json.optBoolean("hasPassword", false),
        )
    }

    private fun parseTag(json: JSONObject): E2ETag {
        return E2ETag(
            id = json.getLong("id"),
            tagName = json.getString("tagName"),
            color = json.optString("color", "#1890ff"),
        )
    }

    private fun parseTeamInvite(json: JSONObject): E2ETeamInvite {
        return E2ETeamInvite(
            id = json.getLong("id"),
            teamId = json.getLong("teamId"),
            email = json.getString("email"),
            role = json.optString("role", "VIEWER"),
            status = json.optString("status", ""),
        )
    }

    private fun parseTeamAuditLog(json: JSONObject): E2ETeamAuditLog {
        return E2ETeamAuditLog(
            id = json.getLong("id"),
            action = json.getString("action"),
            targetType = json.optString("targetType").takeIf { it.isNotBlank() },
            targetId = if (json.isNull("targetId")) null else json.getLong("targetId"),
        )
    }

    private fun parseTeamTask(json: JSONObject) = E2ETeamTask(
        id = json.getLong("id"), taskType = json.getString("taskType"), status = json.getString("status"),
    )

    private fun parseNotification(json: JSONObject): E2ENotification {
        return E2ENotification(
            id = json.getLong("id"),
            teamId = if (json.isNull("teamId")) null else json.getLong("teamId"),
            type = json.getString("type"),
            titleKey = json.getString("titleKey"),
            contentKey = json.getString("contentKey"),
            messageParams = json.getJSONObject("messageParams"),
            link = json.optString("link").takeIf { it.isNotBlank() },
            isRead = json.optBoolean("isRead"),
        )
    }

    private fun parseTeam(json: JSONObject): E2ETeam {
        return E2ETeam(
            id = json.getLong("id"),
            name = json.getString("name"),
            role = json.optString("role", "VIEWER"),
        )
    }

    private fun uniqueSuffix(): String {
        return "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(6)}"
    }

    private fun md5(bytes: ByteArray): String {
        return MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun signOnlyOfficeJwt(payloadJson: String): String {
        val encodedHeader = base64Url("""{"alg":"HS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val encodedPayload = base64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(E2EConfig.onlyOfficeJwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "$signingInput.${base64Url(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))}"
    }

    private fun base64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun minimalDocxBytes(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>${escapeXml(text)}</w:t></w:r></w:p>
                    <w:sectPr>
                      <w:pgSz w:w="12240" w:h="15840"/>
                      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
                    </w:sectPr>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun targetParentBody(targetParentId: Long?): JSONObject {
        return JSONObject().put("targetParentId", if (targetParentId == null) JSONObject.NULL else targetParentId)
    }
}
