package com.dataask.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeConfigCryptoTest {
    private static final String FIRST_SECRET = "dataask-very-long-test-jwt-secret-key-0001";
    private static final String SECOND_SECRET = "dataask-very-long-test-jwt-secret-key-0002";

    private static RuntimeConfigService service(JdbcTemplate jdbc,String secret) {
        RuntimeConfigService runtime=new RuntimeConfigService(jdbc,secret,
            "jdbc:mysql://localhost:9030/ads","readonly","original-sr-password","ads",
            "https://api.example.com/v1","yaml-key","qwen-plus");
        runtime.initializeEncryption();
        return runtime;
    }

    private static void stubStored(JdbcTemplate jdbc, Map<String,String> values) {
        doAnswer(inv -> {
            RowCallbackHandler handler=inv.getArgument(1);
            for (var entry : values.entrySet()) {
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(entry.getKey());
                when(rs.getString(2)).thenReturn(entry.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(eq("SELECT setting_key, setting_value FROM da_app_settings"),
            any(RowCallbackHandler.class));
    }

    @Test
    void emptyEncryptedApiKeyIsValidTwentyEightByteAesGcmBlob() {
        RuntimeConfigService config=service(mock(JdbcTemplate.class),FIRST_SECRET);
        String encoded=ReflectionTestUtils.invokeMethod(config,"encrypt","");
        assertNotNull(encoded);
        assertEquals(28,Base64.getDecoder().decode(encoded.substring(3)).length);
        assertEquals("",ReflectionTestUtils.invokeMethod(config,"decrypt",encoded));
    }

    @Test
    void nonemptyEncryptionStillRoundTripsAndInvalidCiphertextRejects() {
        RuntimeConfigService config=service(mock(JdbcTemplate.class),FIRST_SECRET);
        String encoded=ReflectionTestUtils.invokeMethod(config,"encrypt","another-valid-key");
        assertEquals("another-valid-key",ReflectionTestUtils.invokeMethod(config,"decrypt",encoded));
        IllegalStateException ex=assertThrows(IllegalStateException.class,
            () -> ReflectionTestUtils.invokeMethod(config,"decrypt","v1:AAECAwQ="));
        assertTrue(ex.getMessage().contains("无法解密"));
    }

    @Test
    void clearKeyDoesNotBreakAdminStatusOrSettings() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        RuntimeConfigService config=service(jdbc,FIRST_SECRET);
        String encryptedEmpty=ReflectionTestUtils.invokeMethod(config,"encrypt","");
        stubStored(jdbc,Map.of("ai.apiKey",encryptedEmpty,"embedding.apiKey",encryptedEmpty));
        var admin=config.adminView();
        var status=config.status();
        assertEquals(false,((Map<?,?>)admin.get("ai")).get("apiKeySet"));
        assertEquals(false,((Map<?,?>)admin.get("ai")).get("apiKeyUnreadable"));
        assertEquals(false,status.get("aiConfigured"));
        assertEquals(false,status.get("aiCredentialUnreadable"));
        assertEquals(false,((Map<?,?>)admin.get("embedding")).get("apiKeyUnreadable"));
    }

    @Test
    void damagedAiCredentialDoesNotBlockIndependentAdminModules() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        RuntimeConfigService config=service(jdbc,FIRST_SECRET);
        stubStored(jdbc,Map.of(
            "ai.apiKey","v1:AAECAwQ=",
            "sr.jdbcUrl","jdbc:mysql://localhost:9030/ads",
            "sr.username","readonly"));
        var admin=config.adminView();
        var status=config.status();
        assertEquals(true,((Map<?,?>)admin.get("ai")).get("apiKeyUnreadable"));
        assertEquals(true,status.get("aiCredentialUnreadable"));
        assertEquals(true,status.get("starrocksConfigured"));
        assertEquals(true,((Map<?,?>)admin.get("starrocks")).get("passwordSet"));
        assertFalse(status.containsKey("ai.apiKey"));
    }

    @Test
    void lostEncryptionSecretAllowsExplicitReplacementButNeverLeakingOldKey() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        RuntimeConfigService previous=service(jdbc,FIRST_SECRET);
        String oldCiphertext=ReflectionTestUtils.invokeMethod(previous,"encrypt","old-secret-value");
        stubStored(jdbc,Map.of("ai.apiKey",oldCiphertext));
        RuntimeConfigService newSecret=service(jdbc,SECOND_SECRET);
        assertEquals(true,newSecret.status().get("aiCredentialUnreadable"));
        assertDoesNotThrow(() -> newSecret.saveAi(
            new RuntimeConfigService.ModelInput("https://api.example.com/v1",
                "qwen-plus","new-secret-value",false)));
        assertDoesNotThrow(() -> newSecret.saveEmbedding(
            new RuntimeConfigService.EmbeddingInput("https://api.example.com/v1",
                "embed-test","replacement-embedding-secret",false,true,1024)));
        verify(jdbc,atLeastOnce()).update(contains("da_app_settings"),anyString(),anyString());
    }

    @Test
    void starRocksPasswordCanBeReplacedWithoutDecryptingOldPassword() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        RuntimeConfigService config=service(jdbc,FIRST_SECRET);
        stubStored(jdbc,Map.of("sr.password","v1:AAECAwQ="));
        assertDoesNotThrow(() -> config.validateStarRocks(
            new RuntimeConfigService.StarRocksInput(
                "jdbc:mysql://localhost:9030/ads","readonly","replacement-password","ads")));
        assertDoesNotThrow(() -> config.saveStarRocks(
            new RuntimeConfigService.StarRocksInput(
                "jdbc:mysql://localhost:9030/ads","readonly","replacement-password","ads")));
    }
}
