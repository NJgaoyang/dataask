package com.dataask.config;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Retains the original v1 ciphertext format, so existing stored credentials still work. */
final class CredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    CredentialCipher(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("系统签名密钥必须不少于32字节");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("dataask.runtime.password.v1:".getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(digest.digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) { throw new IllegalStateException("初始化配置加密失败", e); }
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) { throw new IllegalStateException("配置加密失败", e); }
    }

    String decrypt(String value) {
        try {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(value.substring(3));
            if (packed.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("无法解密已保存配置，请恢复原系统密钥，勿删除元数据库配置", e);
        }
    }
}
