package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Base64;

class SharePasswordCryptoServiceImplTest {

    private SharePasswordCryptoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SharePasswordCryptoServiceImpl();
        String key = Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes());
        setEncryptionKey(key);
        service.init();
    }

    @Test
    void encrypt_shouldUseDifferentIvForSameShortPassword() {
        String first = service.encrypt("1234");
        String second = service.encrypt("1234");

        assertThat(first).startsWith("enc:v1:");
        assertThat(second).startsWith("enc:v1:");
        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("1234");
        assertThat(service.decrypt(second)).isEqualTo("1234");
        assertThat(service.matches("1234", first)).isTrue();
        assertThat(service.matches("0000", first)).isFalse();
    }

    private void setEncryptionKey(String key) {
        try {
            Field field = SharePasswordCryptoServiceImpl.class.getDeclaredField("encryptionKey");
            field.setAccessible(true);
            field.set(service, key);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
