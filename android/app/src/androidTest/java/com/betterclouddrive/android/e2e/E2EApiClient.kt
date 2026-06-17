package com.betterclouddrive.android.e2e

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

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
) {
    val isFolder: Boolean get() = fileType == "folder"
}

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

    fun uploadTextFile(
        token: String,
        parentId: Long?,
        fileName: String,
        content: String = "android e2e sample\n",
    ): E2EFile {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val md5 = md5(bytes)
        val initBody = postJson(
            "$baseUrl/upload/init",
            JSONObject()
                .put("parentId", parentId)
                .put("fileName", fileName)
                .put("fileSize", bytes.size)
                .put("md5Hash", md5)
                .put("totalChunks", 1),
            token,
        )
        val sessionId = initBody.getJSONObject("data").getString("sessionId")
        uploadChunk(token, sessionId, fileName, bytes)
        val complete = postJson("$baseUrl/upload/$sessionId/complete", JSONObject(), token)
        val fileId = complete.getJSONObject("data").getLong("fileId")
        return getFile(token, fileId)
    }

    fun addFavorite(token: String, fileId: Long) {
        postJson("$baseUrl/favorites/$fileId", JSONObject(), token)
    }

    fun createShare(token: String, fileId: Long, password: String? = null): E2EShare {
        val payload = JSONObject().put("fileId", fileId)
        if (password != null) payload.put("password", password)
        return parseShare(postJson("$baseUrl/shares", payload, token).getJSONObject("data"))
    }

    fun deleteFiles(token: String, fileIds: List<Long>) {
        val request = Request.Builder()
            .url("$baseUrl/files")
            .delete(JSONObject().put("fileIds", JSONArray(fileIds)).toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $token")
            .build()
        expectOk(client.newCall(request).execute()).close()
    }

    fun restoreFile(token: String, fileId: Long) {
        postJson("$baseUrl/recycle-bin/$fileId/restore", JSONObject(), token)
    }

    fun permanentDeleteFile(token: String, fileId: Long) {
        val request = Request.Builder()
            .url("$baseUrl/recycle-bin/$fileId")
            .delete()
            .header("Authorization", "Bearer $token")
            .build()
        expectApiSuccess(client.newCall(request).execute())
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

    private fun latestMailText(email: String): String {
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
            return detailResponse.body?.string().orEmpty()
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

    private fun postJson(url: String, body: JSONObject, token: String? = null): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return expectApiSuccess(client.newCall(builder.build()).execute())
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

    private fun uniqueSuffix(): String {
        return "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(6)}"
    }

    private fun md5(bytes: ByteArray): String {
        return MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
