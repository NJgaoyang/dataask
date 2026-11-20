package com.dataask.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * One-time, non-destructive upgrade of legacy YAML business settings to metadata MySQL.
 * Existing MySQL keys win; the JWT signing/encryption secret remains deployment-owned.
 */
@Component
public class RuntimeSettingsMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final RuntimeConfigService settings;

    public RuntimeSettingsMigration(JdbcTemplate jdbc, Environment env, RuntimeConfigService settings) {
        this.jdbc=jdbc; this.env=env; this.settings=settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        // MySQL TEXT cannot hold a large field selection policy.
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
            connection.getMetaData().getDatabaseProductName());
        if ("MySQL".equalsIgnoreCase(product)) {
            String type=jdbc.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='da_app_settings' AND COLUMN_NAME='setting_value'", String.class);
            if (!"longtext".equalsIgnoreCase(type))
                jdbc.execute("ALTER TABLE da_app_settings MODIFY setting_value LONGTEXT NOT NULL");
        }
        List<String> migrated=jdbc.query("SELECT setting_value FROM da_app_settings WHERE setting_key='system.config.migrated'",
            (rs,n)->rs.getString(1));
        if (!migrated.isEmpty()) return;
        seed("sr.jdbcUrl","dataask.starrocks.jdbc-url","");
        seed("sr.username","dataask.starrocks.username","");
        seed("sr.schema","dataask.starrocks.schema","ads");
        seedSecret("sr.password","dataask.starrocks.password");
        seed("ai.baseUrl","dataask.ai.base-url","");
        seed("ai.model","dataask.ai.model","");
        seedSecret("ai.apiKey","dataask.ai.api-key");
        seed("embedding.baseUrl","dataask.embedding.base-url","https://dashscope.aliyuncs.com/api/v1");
        seed("embedding.model","dataask.embedding.model","qwen3.7-text-embedding-flash");
        seed("embedding.enabled","dataask.embedding.enabled","false");
        seed("embedding.dimensions","dataask.embedding.dimensions","1024");
        seedSecret("embedding.apiKey","dataask.embedding.api-key");
        insertAbsent("system.config.migrated","3");
    }

    private void seed(String key,String property,String fallback) {
        String input=env.getProperty(property);
        insertAbsent(key,input==null || input.isBlank()?fallback:input.trim());
    }
    private void seedSecret(String key,String property) {
        String input=env.getProperty(property,"");
        insertAbsent(key,settings.sealCredential(input));
    }
    private void insertAbsent(String key,String value) {
        jdbc.update("INSERT INTO da_app_settings(setting_key,setting_value) VALUES(?,?) " +
            "ON DUPLICATE KEY UPDATE setting_key=setting_key",key,value);
    }
}
