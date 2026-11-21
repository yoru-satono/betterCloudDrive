package com.betterclouddrive.storage.s3;

import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.assertj.core.api.Assertions.assertThat;

class StorageFailurePrivacyTest {
    @Test void excludesRawHeadersMessagesAndNestedCausesFromRenderedFailures() {
        var raw = new IllegalStateException("Authorization: synthetic-signature", new java.io.IOException("private response body"));
        var safe = S3StorageServiceImpl.safeFailure("compose", raw);
        var output = new StringWriter();
        safe.printStackTrace(new PrintWriter(output));
        assertThat(output.toString()).contains("compose", "IOException", "code=unavailable")
                .doesNotContain("synthetic-signature", "private response body", "Authorization");
        assertThat(safe.getCause()).isNull();
        assertThat(S3StorageServiceImpl.safeFailure("upload", safe)).isSameAs(safe);
        assertThat(safe.getStackTrace()).containsExactly(raw.getStackTrace());
    }
}
