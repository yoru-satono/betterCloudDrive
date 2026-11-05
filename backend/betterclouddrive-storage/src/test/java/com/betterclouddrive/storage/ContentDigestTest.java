package com.betterclouddrive.storage;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ContentDigestTest {
    @Test void computesActualDigestAcrossShortReadsAndSkips() throws Exception {
        var input = new ByteArrayInputStream("hello world".getBytes()) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(2, length));
            }
        };
        try (var stream = new ContentDigest.Stream(input)) {
            assertThat(stream.read()).isEqualTo('h');
            assertThat(stream.skip(4)).isEqualTo(4);
            stream.readAllBytes();
            assertThat(stream.result()).isEqualTo(new ContentDigest(11, "5eb63bbbe01eeed093cb22bb8f5acdc3"));
        }
    }

    @Test void emptyContentHasCanonicalMd5() throws Exception {
        assertThat(ContentDigest.read(new ByteArrayInputStream(new byte[0])))
                .isEqualTo(new ContentDigest(0, "d41d8cd98f00b204e9800998ecf8427e"));
    }
}
