package com.bdic.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 用户密码处理工具。
 *
 * <p>服务端不保存明文密码，只保存随机盐和 PBKDF2 派生出的密码摘要。</p>
 */
public class PasswordUtil {

    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;

    /**
     * 为每个用户生成独立随机盐。
     */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * 使用 PBKDF2WithHmacSHA256 生成加盐密码摘要。
     */
    public static byte[] hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("生成密码摘要失败", e);
        }
    }

    /**
     * 校验用户输入密码是否与数据库中的盐和摘要匹配。
     */
    public static boolean matches(String password, byte[] salt, byte[] expectedHash) {
        byte[] actualHash = hashPassword(password, salt);
        return Arrays.equals(actualHash, expectedHash);
    }
}
