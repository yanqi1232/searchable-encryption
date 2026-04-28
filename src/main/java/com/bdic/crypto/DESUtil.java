package com.bdic.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;

/**
 * DES 对称加密工具类。
 *
 * <p>本项目使用它加密文档正文和关键词元数据。DES 主要用于课程/演示场景，
 * 如果用于真实生产系统，建议替换为 AES-GCM 等现代认证加密算法。</p>
 */
public class DESUtil {

    private static final String ALGORITHM = "DES";

    /**
     * 生成新的 DES 密钥。
     */
    public static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            // DES 的有效密钥长度固定为 56 位。
            keyGen.init(56);
            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error generating DES key", e);
        }
    }

    /**
     * 根据持久化的原始字节恢复 DES 密钥。
     */
    public static SecretKey getKeyFromBytes(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 使用指定密钥加密明文字节。
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(plaintext);
    }

    /**
     * 使用指定密钥解密密文字节。
     */
    public static byte[] decrypt(byte[] ciphertext, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(ciphertext);
    }
}
