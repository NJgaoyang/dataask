package com.dataask.config;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CredentialCipherTest {
    private final String secret="test-only-signing-key-with-at-least-32-bytes";
    @Test void roundTripsEmptyKeyAndUnicodePassword() {
        var cipher=new CredentialCipher(secret);
        for(String value:new String[]{"", "测试-connection-key", "example-secret"}) {
            String stored=cipher.encrypt(value);
            assertThat(stored).startsWith("v1:");
            assertThat(new CredentialCipher(secret).decrypt(stored)).isEqualTo(value);
        }
    }
    @Test void rejectsWrongInstallationKey() {
        String stored=new CredentialCipher(secret).encrypt("example");
        assertThatThrownBy(() -> new CredentialCipher(secret+"changed").decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }
    @Test void hostedDefaultsAreNotConfiguredWithoutKey() {
        assertThat(new RuntimeConfigService.AiConfig("https://api.deepseek.com", "", "deepseek-flash").configured()).isFalse();
        assertThat(new RuntimeConfigService.EmbeddingConfig("https://dashscope.aliyuncs.com/api/v1", "", "qwen3.7-text-embedding-flash", true, 1024).configured()).isFalse();
        assertThat(new RuntimeConfigService.AiConfig("http://127.0.0.1:9000/v1", "", "local-test").configured()).isTrue();
    }
}
