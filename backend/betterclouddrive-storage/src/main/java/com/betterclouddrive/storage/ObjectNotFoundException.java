package com.betterclouddrive.storage;

/** An absent object, distinct from an unavailable or unauthorized storage service. */
public class ObjectNotFoundException extends RuntimeException {
    public ObjectNotFoundException(Throwable cause) { super("Storage object not found", cause); }
}
