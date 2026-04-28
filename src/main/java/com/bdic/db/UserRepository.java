package com.bdic.db;

import com.bdic.crypto.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 用户仓储。
 *
 * <p>负责注册新用户、校验登录凭据，并将密码以“盐 + 摘要”的形式写入数据库。</p>
 */
public class UserRepository {

    /** 数据库访问入口。 */
    private final DatabaseManager databaseManager;

    /** 注入数据库管理器，供仓储执行持久化操作。 */
    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * 注册新用户；用户名已存在时返回 false。
     */
    public boolean register(String username, String password) {
        // 将用户名、密码摘要和盐值写入用户表。
        String sql = "INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)";
        // 为每个用户生成独立盐值，并基于盐值计算密码摘要。
        byte[] salt = PasswordUtil.generateSalt();
        byte[] hash = PasswordUtil.hashPassword(password, salt);

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setBytes(2, hash);
            statement.setBytes(3, salt);
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            // 主键或唯一索引冲突，说明用户名已经存在。
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw new RuntimeException("注册用户失败", e);
        }
    }

    /**
     * 校验用户名和密码是否匹配。
     */
    public boolean authenticate(String username, String password) {
        // 根据用户名查询已保存的密码摘要和盐值。
        String sql = "SELECT password_hash, password_salt FROM users WHERE username = ?";

        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                // 用户不存在时直接认证失败。
                if (!resultSet.next()) {
                    return false;
                }

                // 读取数据库中的摘要与盐值，并校验输入密码。
                byte[] passwordHash = resultSet.getBytes("password_hash");
                byte[] passwordSalt = resultSet.getBytes("password_salt");
                return PasswordUtil.matches(password, passwordSalt, passwordHash);
            }
        } catch (SQLException e) {
            throw new RuntimeException("用户认证失败", e);
        }
    }
}
