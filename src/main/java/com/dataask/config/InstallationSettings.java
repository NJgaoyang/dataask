package com.dataask.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Only metadata-MySQL bootstrap connection/HTTP listener must exist before this bean. */
@Component
@DependsOnDatabaseInitialization
public class InstallationSettings {
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final TransactionTemplate tx;
    private String signingKey;

    public InstallationSettings(JdbcTemplate jdbc, Environment env, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.env = env;
        this.tx = new TransactionTemplate(manager);
    }

    @PostConstruct
    void initialize() {
        // Idempotent upgrade: a catalog of many selected columns can exceed TEXT's 64 KiB.
        String type = jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='da_app_settings' AND COLUMN_NAME='setting_value'", String.class);
        if (!"longtext".equalsIgnoreCase(type)) jdbc.execute("ALTER TABLE da_app_settings MODIFY setting_value LONGTEXT NOT NULL");
        tx.executeWithoutResult(status -> {
            insertAbsent("system.bootstrap.lock", "1");
            jdbc.queryForObject("SELECT setting_value FROM da_app_settings WHERE setting_key=? FOR UPDATE",
                String.class, "system.bootstrap.lock");
            String stored = read("system.jwtSecret");
            if (stored == null) {
                String legacy = env.getProperty("dataask.jwt-secret", env.getProperty("DATAASK_JWT_SECRET", "")).trim();
                if (legacy.isBlank()) {
                    Integer encrypted = jdbc.queryForObject("SELECT COUNT(*) FROM da_app_settings " +
                        "WHERE setting_key IN ('sr.password','ai.apiKey','embedding.apiKey')", Integer.class);
                    if (encrypted != null && encrypted > 0)
                        throw new IllegalStateException("首次迁移数据库配置时必须保留旧 JWT 密钥，避免已有连接密码无法解密");
                    legacy = randomSecret();
                }
                // Validate before writing; subsequent starts use the DB value, not a new random key.
                new CredentialCipher(legacy);
                insertAbsent("system.jwtSecret", legacy);
                stored = read("system.jwtSecret");
            }
            signingKey = stored;
            CredentialCipher cipher = new CredentialCipher(signingKey);
            if (read("system.config.migrated") == null) {
                seed("sr.jdbcUrl", "dataask.starrocks.jdbc-url", "");
                seed("sr.username", "dataask.starrocks.username", "");
                seed("sr.schema", "dataask.starrocks.schema", "ads");
                seedSecret("sr.password", "dataask.starrocks.password", cipher);
                seed("ai.baseUrl", "dataask.ai.base-url", "https://api.deepseek.com");
                seed("ai.model", "dataask.ai.model", "deepseek-flash");
                seedSecret("ai.apiKey", "dataask.ai.api-key", cipher);
                seed("embedding.baseUrl", "dataask.embedding.base-url", "https://dashscope.aliyuncs.com/api/v1");
                seed("embedding.model", "dataask.embedding.model", "qwen3.7-text-embedding-flash");
                seed("embedding.dimensions", "dataask.embedding.dimensions", "1024");
                seed("embedding.enabled", "dataask.embedding.enabled", "false");
                seedSecret("embedding.apiKey", "dataask.embedding.api-key", cipher);
                insertAbsent("system.config.migrated", "2");
            }
        });
    }

    private void seed(String key, String property, String fallback) {
        String value = env.getProperty(property, "").trim();
        insertAbsent(key, value.isBlank() ? fallback : value);
    }

    private void seedSecret(String key, String property, CredentialCipher cipher) {
        String value = env.getProperty(property, "");
        insertAbsent(key, cipher.encrypt(value));
    }

    private void insertAbsent(String key, String value) {
        jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES(?,?) " +
            "ON DUPLICATE KEY UPDATE setting_key=setting_key", key, value);
    }

    private String read(String key) {
        List<String> values = jdbc.query("SELECT setting_value FROM da_app_settings WHERE setting_key=?",
            (rs, n) -> rs.getString(1), key);
        return values.isEmpty() ? null : values.getFirst();
    }

    public String signingKey() { return signingKey; }

    public static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
