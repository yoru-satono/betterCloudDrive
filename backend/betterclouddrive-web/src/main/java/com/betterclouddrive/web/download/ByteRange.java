package com.betterclouddrive.web.download;

import org.springframework.http.HttpHeaders;

public record ByteRange(long start, long end, long totalSize) {

    public long length() {
        return end - start + 1;
    }

    public String contentRangeHeader() {
        return "bytes " + start + "-" + end + "/" + totalSize;
    }

    public static ByteRange full(long totalSize) {
        return new ByteRange(0, Math.max(0, totalSize - 1), totalSize);
    }

    public static ByteRange parse(String rangeHeader, long totalSize) {
        if (totalSize < 0) {
            throw new IllegalArgumentException("Invalid object size");
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        if (!rangeHeader.startsWith("bytes=")) {
            throw new IllegalArgumentException("Unsupported range unit");
        }

        String value = rangeHeader.substring("bytes=".length()).trim();
        if (value.contains(",")) {
            throw new IllegalArgumentException("Multiple ranges are not supported");
        }

        String[] parts = value.split("-", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid range");
        }

        long start;
        long end;
        if (parts[0].isBlank()) {
            long suffixLength = parseNonNegative(parts[1]);
            if (suffixLength <= 0) {
                throw new IllegalArgumentException("Invalid suffix range");
            }
            start = Math.max(0, totalSize - suffixLength);
            end = totalSize - 1;
        } else {
            start = parseNonNegative(parts[0]);
            end = parts[1].isBlank() ? totalSize - 1 : parseNonNegative(parts[1]);
        }

        if (totalSize == 0 || start >= totalSize || end < start) {
            throw new IllegalArgumentException("Range not satisfiable");
        }

        return new ByteRange(start, Math.min(end, totalSize - 1), totalSize);
    }

    public static String unsatisfiedRangeHeader(long totalSize) {
        return "bytes */" + totalSize;
    }

    public HttpHeaders headers(String contentDisposition) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        headers.set(HttpHeaders.CONTENT_RANGE, contentRangeHeader());
        headers.setContentLength(length());
        return headers;
    }

    private static long parseNonNegative(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("Negative range");
        }
        return parsed;
    }
}
