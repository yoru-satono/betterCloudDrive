package com.betterclouddrive.storage;

/** Deliberately excludes object names, credentials and remote error bodies. */
public class StorageInventoryException extends RuntimeException {
    public StorageInventoryException(String code) { super(code); }
}
