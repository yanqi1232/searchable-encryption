package com.bdic.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库连接和表结构初始化入口。
 *
 * <p>配置读取顺序为：JVM 系统属性、环境变量、默认值。启动服务端时会自动创建数据库、
 * 用户表、文档表和关键词索引表，并兼容旧版本缺失字段。</p>
 */
public class DatabaseManager {

    /** 数据库主机地址。 */
    private final String host;
    /** 数据库服务端口。 */
    private final int port;
    /** 业务数据库名称。 */
    private final String databaseName;
    /** 数据库登录用户名。 */
    private final String username;
    /** 数据库登录密码。 */
    private final String password;

    /** 使用系统属性、环境变量或默认值构造数据库连接配置。 */
    public DatabaseManager() {
        this(
                getValue("se.db.host", "SE_DB_HOST", "localhost"),
                Integer.parseInt(getValue("se.db.port", "SE_DB_PORT", "3306")),
                getValue("se.db.name", "SE_DB_NAME", "searchable_encryption"),
                getValue("se.db.user", "SE_DB_USER", "root"),
                getValue("se.db.password", "SE_DB_PASSWORD", "123456ysy")
        );
    }

    /** 使用显式传入的数据库参数构造管理器。 */
    public DatabaseManager(String host, int port, String databaseName, String username, String password) {
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;
    }

    /** 获取指向业务数据库的 JDBC 连接。 */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(buildDatabaseJdbcUrl(), username, password);
    }

    /**
     * 创建业务数据库和必要表结构。重复执行是安全的，适合服务端每次启动时调用。
     */
    public void initialize() {
        try (Connection serverConnection = DriverManager.getConnection(buildServerJdbcUrl(), username, password);
             Statement serverStatement = serverConnection.createStatement()) {
            serverStatement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + databaseName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database", e);
        }

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    username VARCHAR(100) PRIMARY KEY,
                    password_hash VARBINARY(255) NOT NULL,
                    password_salt VARBINARY(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS documents (
                    doc_id VARCHAR(255) PRIMARY KEY,
                    display_doc_id VARCHAR(255) NOT NULL,
                    owner_username VARCHAR(100) NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    mime_type VARCHAR(255) NOT NULL,
                    media_type VARCHAR(50) NOT NULL,
                    file_size BIGINT NOT NULL DEFAULT 0,
                    encrypted_keyword_metadata LONGBLOB NULL,
                    encrypted_content LONGBLOB NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS keyword_index (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    doc_id VARCHAR(255) NOT NULL,
                    peks_ciphertext VARBINARY(2048) NOT NULL,
                    CONSTRAINT fk_keyword_document
                        FOREIGN KEY (doc_id) REFERENCES documents(doc_id)
                        ON DELETE CASCADE
                )
                """);

            // 以下字段补齐用于兼容历史表结构，避免升级后手动迁移。
            ensureColumnExists(statement, "documents", "display_doc_id", "VARCHAR(255) NULL");
            ensureColumnExists(statement, "documents", "owner_username", "VARCHAR(100) NOT NULL DEFAULT 'system'");
            ensureColumnExists(statement, "documents", "file_name", "VARCHAR(255) NOT NULL DEFAULT 'unknown.bin'");
            ensureColumnExists(statement, "documents", "mime_type", "VARCHAR(255) NOT NULL DEFAULT 'application/octet-stream'");
            ensureColumnExists(statement, "documents", "media_type", "VARCHAR(50) NOT NULL DEFAULT 'binary'");
            ensureColumnExists(statement, "documents", "file_size", "BIGINT NOT NULL DEFAULT 0");
            ensureColumnExists(statement, "documents", "encrypted_keyword_metadata", "LONGBLOB NULL");
            ensureColumnExists(statement, "users", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            ensureColumnExists(statement, "documents", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
            statement.executeUpdate("UPDATE documents SET display_doc_id = doc_id WHERE display_doc_id IS NULL OR display_doc_id = ''");
            modifyColumn(statement, "keyword_index", "peks_ciphertext", "VARBINARY(2048) NOT NULL");

            // 为常用查询条件建立索引，提升按用户列文档和搜索的效率。
            if (!indexExists(connection, "documents", "idx_documents_owner")) {
                statement.executeUpdate("CREATE INDEX idx_documents_owner ON documents(owner_username)");
            }

            if (!indexExists(connection, "documents", "idx_documents_owner_display")) {
                statement.executeUpdate("CREATE INDEX idx_documents_owner_display ON documents(owner_username, display_doc_id)");
            }

            if (!indexExists(connection, "keyword_index", "idx_keyword_doc")) {
                statement.executeUpdate("CREATE INDEX idx_keyword_doc ON keyword_index(doc_id)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    private void ensureColumnExists(Statement statement, String tableName, String columnName, String columnDefinition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        } catch (SQLException e) {
            // 1060 表示列已存在，说明当前库结构已满足要求。
            if (e.getErrorCode() != 1060) {
                throw e;
            }
        }
    }

    /** 调整已有列定义；目标列不存在时交由后续逻辑兼容处理。 */
    private void modifyColumn(Statement statement, String tableName, String columnName, String columnDefinition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + tableName + " MODIFY COLUMN " + columnName + " " + columnDefinition);
        } catch (SQLException e) {
            // 1054 表示列不存在，此处忽略以兼容历史版本。
            if (e.getErrorCode() != 1054) {
                throw e;
            }
        }
    }

    /** 检查指定表上是否已经存在目标索引。 */
    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (resultSet.next()) {
                String existingIndexName = resultSet.getString("INDEX_NAME");
                if (indexName.equalsIgnoreCase(existingIndexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 构造连接数据库服务器本身的 JDBC URL，用于建库。 */
    private String buildServerJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }

    /** 构造连接业务数据库的 JDBC URL。 */
    private String buildDatabaseJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8";
    }

    /**
     * 优先读取 JVM 参数，其次读取环境变量，最后使用默认值。
     */
    private static String getValue(String propertyKey, String envKey, String defaultValue) {
        String propertyValue = System.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return defaultValue;
    }
}
