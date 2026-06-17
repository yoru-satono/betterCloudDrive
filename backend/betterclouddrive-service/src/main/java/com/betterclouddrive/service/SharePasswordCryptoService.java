package com.betterclouddrive.service;

public interface SharePasswordCryptoService {
    String encrypt(String plainText);
    String decrypt(String cipherText);
    boolean matches(String plainText, String cipherText);
}
