package com.betterclouddrive.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int page;
    private int size;
    private int pages;

    public static <T> PageResult<T> empty(int page, int size) {
        return PageResult.<T>builder()
                .records(Collections.emptyList())
                .total(0)
                .page(page)
                .size(size)
                .pages(0)
                .build();
    }

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return PageResult.<T>builder()
                .records(records)
                .total(total)
                .page(page)
                .size(size)
                .pages(size > 0 ? (int) Math.ceil((double) total / size) : 0)
                .build();
    }
}
