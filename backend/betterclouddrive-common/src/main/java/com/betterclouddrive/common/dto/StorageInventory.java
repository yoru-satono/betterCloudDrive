package com.betterclouddrive.common.dto;

import java.time.Instant;
import java.util.List;

public final class StorageInventory {
    private StorageInventory() {}
    public record ObjectInfo(String key, long size, String etag, Instant modifiedAt) {
        @Override public String toString() { return "<storage object metadata>"; }
    }
    public record Page(List<ObjectInfo> objects, String nextToken, boolean complete) {
        public Page { objects = List.copyOf(objects); }
        @Override public String toString() { return "<storage inventory page, count=" + objects.size() + ">"; }
    }
}
