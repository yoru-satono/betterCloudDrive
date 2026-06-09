package com.betterclouddrive.common.dto;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class PageResultTest {

    @Test
    void shouldCreatePageResult() {
        List<String> records = List.of("a", "b", "c");
        PageResult<String> result = PageResult.of(records, 100, 1, 20);

        assertThat(result.getRecords()).isEqualTo(records);
        assertThat(result.getTotal()).isEqualTo(100);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getPages()).isEqualTo(5);
    }

    @Test
    void shouldCalculatePagesCorrectly() {
        PageResult<String> result1 = PageResult.of(List.of(), 15, 1, 10);
        assertThat(result1.getPages()).isEqualTo(2);

        PageResult<String> result2 = PageResult.of(List.of(), 10, 1, 10);
        assertThat(result2.getPages()).isEqualTo(1);

        PageResult<String> result3 = PageResult.of(List.of(), 0, 1, 10);
        assertThat(result3.getPages()).isEqualTo(0);
    }

    @Test
    void shouldCreateEmptyResult() {
        PageResult<String> result = PageResult.empty(1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getPages()).isEqualTo(0);
    }
}
