package com.starkgroup.vms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CorsPropertiesTest {

    @Test
    void originsAreTrimmedAndBlanksDropped() {
        CorsProperties properties = new CorsProperties(List.of(" https://vms.netlify.app ", ""), null);

        assertThat(properties.allowedOrigins()).containsExactly("https://vms.netlify.app");
        assertThat(properties.allowedOriginPatterns()).isEmpty();
    }

    @Test
    void wildcardOriginIsRejectedAtStartup() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'*' is not allowed");
        assertThatThrownBy(() -> new CorsProperties(List.of(), List.of(" * ")))
                .isInstanceOf(IllegalStateException.class);
    }
}
