package com.dataask.semantic;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EmbeddingEndpointTest {
    @Test
    void recognisesUserDashscopeNativeUrl() {
        assertThat(EmbeddingSemanticService.isDashScopeNative(
            "https://dashscope.aliyuncs.com/api/v1")).isTrue();
    }

    @Test
    void distinguishesOpenAiCompatibilityMode() {
        assertThat(EmbeddingSemanticService.isDashScopeNative(
            "https://dashscope.aliyuncs.com/compatible-mode/v1")).isFalse();
        assertThat(EmbeddingSemanticService.isDashScopeNative(
            "http://127.0.0.1:8000/v1")).isFalse();
    }
}
