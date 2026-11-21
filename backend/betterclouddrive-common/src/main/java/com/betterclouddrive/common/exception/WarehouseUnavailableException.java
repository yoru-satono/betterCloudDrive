package com.betterclouddrive.common.exception;

/** No endpoint, credentials, source SQL or remote error body may reach clients. */
public class WarehouseUnavailableException extends RuntimeException {
    public WarehouseUnavailableException() { super("Warehouse report unavailable"); }
}
