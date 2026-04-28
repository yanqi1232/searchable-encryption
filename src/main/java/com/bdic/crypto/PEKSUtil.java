package com.bdic.crypto;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 可搜索关键词加密工具类。
 *
 * <p>当前实现使用 HMAC-SHA256 模拟 PEKS 的三个核心操作：
 * 生成关键词密文、生成查询陷门、服务器侧匹配测试。这样可以演示“服务器不接触明文关键词”
 * 的完整流程。若后续接入 JPBC/双线性对库，可在本类内部替换算法，外部调用点无需大改。</p>
 */
public class PEKSUtil {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 生成搜索密钥。演示实现中加密关键词和生成陷门使用同一把 HMAC 密钥。
     */
    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
        keyGen.init(256);
        return keyGen.generateKey();
    }

    /**
     * 根据本地保存的原始字节恢复搜索密钥。
     */
    public static SecretKey getKeyFromBytes(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 为关键词生成可搜索密文，随文档一起保存到服务端索引表。
     */
    public static byte[] encrypt(SecretKey publicSearchKey, String keyword) throws Exception {
        return generateMac(publicSearchKey, keyword);
    }

    /**
     * 为用户查询词生成陷门，服务端用它和关键词密文做匹配。
     */
    public static byte[] getTrapdoor(SecretKey privateSearchKey, String query) throws Exception {
        return generateMac(privateSearchKey, query);
    }

    /**
     * 兼容旧代码入口，内部直接转到正式的陷门生成方法。
     */
    public static byte[] getInternalTrapdoor(SecretKey key, String keyword) throws Exception {
        return getTrapdoor(key, keyword);
    }

    /**
     * 常量时间比较，避免普通数组比较带来的时序侧信道风险。
     */
    public static boolean test(byte[] peksCiphertext, byte[] trapdoor) {
        return MessageDigest.isEqual(peksCiphertext, trapdoor);
    }

    private static byte[] generateMac(SecretKey key, String keyword) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(key);
        return mac.doFinal(normalizeKeyword(keyword).getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim().toLowerCase(Locale.ROOT);
    }
}
