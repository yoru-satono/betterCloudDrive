package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.service.SharePasswordCryptoService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SharePasswordCryptoServiceImpl implements SharePasswordCryptoService {

    private static final String PREFIX = "enc:v1:";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    @Value("${share.password-encryption-key:}")
    private String encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec secretKey;

    @PostConstruct
    public void init() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("share.password-encryption-key is not configured");
        }
        byte[] keyBytes = decodeKey(encryptionKey.trim());
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("share.password-encryption-key must decode to 32 bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }
        ensureReady();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.trim().getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipherBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt share password", e);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        ensureReady();
        try {
            ParsedCipher parsed = parseCipherText(cipherText.trim());
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, parsed.iv));
            byte[] plainBytes = cipher.doFinal(parsed.cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new BusinessException(400, "分享密码已失效或格式错误");
        }
    }

    @Override
    public boolean matches(String plainText, String cipherText) {
        if (plainText == null || cipherText == null || plainText.isBlank() || cipherText.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                decrypt(cipherText).getBytes(StandardCharsets.UTF_8),
                plainText.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    private void ensureReady() {
        if (secretKey == null) {
            throw new IllegalStateException("share.password-encryption-key is not initialized");
        }
    }

    private byte[] decodeKey(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        if (raw.length == KEY_LENGTH_BYTES) {
            return raw;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(key);
            if (decoded.length == KEY_LENGTH_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to deterministic derivation for short development keys.
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid share.password-encryption-key", e);
        }
    }

    private ParsedCipher parseCipherText(String cipherText) {
        if (!cipherText.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Unsupported cipher format");
        }
        String[] parts = cipherText.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cipher format");
        }
        return new ParsedCipher(
                Base64.getDecoder().decode(parts[0]),
                Base64.getDecoder().decode(parts[1])
        );
    }

    private record ParsedCipher(byte[] iv, byte[] cipherBytes) {}
}
