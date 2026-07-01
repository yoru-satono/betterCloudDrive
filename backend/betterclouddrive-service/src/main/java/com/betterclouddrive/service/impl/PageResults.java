package com.betterclouddrive.service.impl;

import com.betterclouddrive.common.dto.PageResult;

import java.util.List;

final class PageResults {

    private PageResults() {
    }

    static <T> PageResult<T> fromFiltered(List<T> filtered, int page, int size) {
        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("page and size must be positive");
        }
        int fromIndex = Math.min((page - 1) * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());
        return PageResult.of(filtered.subList(fromIndex, toIndex), filtered.size(), page, size);
    }
}
