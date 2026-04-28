package com.bdic.crypto;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;

/**
 * 客户端密钥管理器。
 *
 * <p>每个用户在本机拥有稳定的 DES 密钥和搜索密钥。这样客户端重启后，
 * 仍然可以解密旧文档并生成能匹配旧索引的陷门。</p>
 */
public class ClientKeyManager {

    private final Path keyDirectory;

    public ClientKeyManager() {
        this(Paths.get(System.getProperty("user.home"), ".searchable-encryption", "client-keys"));
    }

    public ClientKeyManager(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    /**
     * 加载指定用户的本地密钥；不存在时自动生成并保存。
     */
    public KeyBundle loadOrCreate(String username) {
        try {
            Files.createDirectories(keyDirectory);
            Path keyFile = keyDirectory.resolve(username + ".properties");
            migrateLegacyKeyFile(username, keyFile);
            if (Files.exists(keyFile)) {
                return load(keyFile);
            }

            SecretKey desKey = DESUtil.generateKey();
            SecretKey peksKey = PEKSUtil.generateKey();
            save(keyFile, desKey, peksKey);
            return new KeyBundle(desKey, peksKey);
        } catch (Exception e) {
            throw new RuntimeException("加载客户端密钥失败", e);
        }
    }

    /**
     * 兼容早期项目目录下的 client-keys 文件夹，自动迁移到用户目录。
     */
    private void migrateLegacyKeyFile(String username, Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return;
        }

        Path legacyDirectory = Paths.get("client-keys");
        Path legacyKeyFile = legacyDirectory.resolve(username + ".properties");
        if (!Files.exists(legacyKeyFile)) {
            return;
        }

        Files.createDirectories(keyDirectory);
        Files.copy(legacyKeyFile, keyFile);
    }

    /**
     * 从 properties 文件恢复 DES 密钥和搜索密钥。
     */
    private KeyBundle load(Path keyFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(keyFile)) {
            properties.load(inputStream);
        }

        byte[] desBytes = Base64.getDecoder().decode(properties.getProperty("desKey"));
        byte[] peksBytes = Base64.getDecoder().decode(properties.getProperty("peksKey"));
        return new KeyBundle(DESUtil.getKeyFromBytes(desBytes), PEKSUtil.getKeyFromBytes(peksBytes));
    }

    /**
     * 将密钥以 Base64 形式保存到本地 properties 文件。
     */
    private void save(Path keyFile, SecretKey desKey, SecretKey peksKey) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("desKey", Base64.getEncoder().encodeToString(desKey.getEncoded()));
        properties.setProperty("peksKey", Base64.getEncoder().encodeToString(peksKey.getEncoded()));

        try (OutputStream outputStream = Files.newOutputStream(keyFile)) {
            properties.store(outputStream, null);
        }
    }

    /**
     * 当前用户的一组客户端密钥。
     */
    public record KeyBundle(SecretKey desKey, SecretKey peksKey) {
    }
}
