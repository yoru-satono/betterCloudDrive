package com.betterclouddrive.storage;

import com.betterclouddrive.common.dto.StorageInventory.ObjectInfo;
import com.betterclouddrive.common.dto.StorageInventory.Page;
import java.util.Optional;

/** Read-only inspection boundary. Missing objects are distinct from storage failures. */
public interface StorageInventoryService {
    Page list(String continuationToken, int limit);
    Optional<ObjectInfo> stat(String key);
}
