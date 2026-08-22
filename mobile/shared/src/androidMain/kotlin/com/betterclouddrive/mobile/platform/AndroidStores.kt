package com.betterclouddrive.mobile.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.betterclouddrive.mobile.core.model.StoredSession
import com.betterclouddrive.mobile.core.model.SessionStorageInvalidException
import com.betterclouddrive.mobile.core.network.BackendClient
import com.betterclouddrive.mobile.core.platform.SecureSessionStore
import com.betterclouddrive.mobile.core.platform.ServerSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun createPreferencesStore(context: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }

internal class AndroidServerSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : ServerSettingsStore {
    override val serverBaseUrl: Flow<String> = dataStore.data.map { it[SERVER_KEY] ?: BackendClient.DEFAULT_SERVER }
    override suspend fun read(): String = dataStore.data.first()[SERVER_KEY] ?: BackendClient.DEFAULT_SERVER
    override suspend fun write(value: String) { dataStore.edit { it[SERVER_KEY] = value } }

    private companion object {
        val SERVER_KEY = stringPreferencesKey("server_base_url")
    }
}

class AndroidSecureSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val applicationId: String,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecureSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(serverBaseUrl: String): StoredSession? {
        val encoded = dataStore.data.first()[PAYLOAD_KEY] ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_SIZE)
            val iv = packed.copyOfRange(0, IV_SIZE)
            val ciphertext = packed.copyOfRange(IV_SIZE, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(serverBaseUrl))
            json.decodeFromString<StoredSession>(cipher.doFinal(ciphertext).decodeToString()).also {
                require(it.version == FORMAT_VERSION)
                require(it.serverBaseUrl == serverBaseUrl)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            clearAndRotateKey()
            throw SessionStorageInvalidException()
        }
    }

    override suspend fun write(session: StoredSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad(session.serverBaseUrl))
        val ciphertext = cipher.doFinal(json.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        val packed = cipher.iv + ciphertext
        dataStore.edit { it[PAYLOAD_KEY] = Base64.encodeToString(packed, Base64.NO_WRAP) }
    }

    override suspend fun clear() { dataStore.edit { it.remove(PAYLOAD_KEY) } }

    suspend fun rawPayloadForTest(): String? = dataStore.data.first()[PAYLOAD_KEY]
    suspend fun corruptPayloadForTest() { dataStore.edit { it[PAYLOAD_KEY] = "not-valid-base64" } }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private suspend fun clearAndRotateKey() {
        clear()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(keyAlias) }
        }
    }

    private fun aad(server: String): ByteArray = "$applicationId|$server|$FORMAT_VERSION".encodeToByteArray()

    private companion object {
        val PAYLOAD_KEY = stringPreferencesKey("encrypted_session_v1")
        const val FORMAT_VERSION = 1
        const val IV_SIZE = 12
        const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "betterclouddrive.mobile.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
