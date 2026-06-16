package com.betterclouddrive.web.download;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ByteRangeTest {

    @Test
    void parsesClosedRange() {
        ByteRange range = ByteRange.parse("bytes=5-9", 20);

        assertThat(range.start()).isEqualTo(5);
        assertThat(range.end()).isEqualTo(9);
        assertThat(range.length()).isEqualTo(5);
        assertThat(range.contentRangeHeader()).isEqualTo("bytes 5-9/20");
    }

    @Test
    void parsesOpenEndedRange() {
        ByteRange range = ByteRange.parse("bytes=5-", 20);

        assertThat(range.start()).isEqualTo(5);
        assertThat(range.end()).isEqualTo(19);
    }

    @Test
    void parsesSuffixRange() {
        ByteRange range = ByteRange.parse("bytes=-4", 20);

        assertThat(range.start()).isEqualTo(16);
        assertThat(range.end()).isEqualTo(19);
    }

    @Test
    void rejectsUnsatisfiedRange() {
        assertThatThrownBy(() -> ByteRange.parse("bytes=20-", 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
