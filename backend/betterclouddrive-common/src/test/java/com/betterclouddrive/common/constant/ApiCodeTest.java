package com.betterclouddrive.common.constant;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class ApiCodeTest {

    @Test
    void shouldHaveUniqueCodes() {
        Set<Integer> codes = new HashSet<>();
        for (ApiCode apiCode : ApiCode.values()) {
            assertThat(codes.add(apiCode.getCode()))
                    .as("Duplicate code %d found in %s", apiCode.getCode(), apiCode.name())
                    .isTrue();
        }
    }

    @Test
    void shouldHaveNonEmptyMessages() {
        for (ApiCode apiCode : ApiCode.values()) {
            assertThat(apiCode.getMessage())
                    .as("Message for %s should not be null or empty", apiCode.name())
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    void shouldContainExpectedErrorCategories() {
        assertThat(ApiCode.TOKEN_EXPIRED.getCode()).isEqualTo(401001);
        assertThat(ApiCode.STORAGE_ERROR.getCode()).isEqualTo(500001);
        assertThat(ApiCode.STORAGE_QUOTA_EXCEEDED.getCode()).isEqualTo(419001);
    }
}
