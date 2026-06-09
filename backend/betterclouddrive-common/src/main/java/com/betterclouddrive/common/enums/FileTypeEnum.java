package com.betterclouddrive.common.enums;

public enum FileTypeEnum {
    FILE("file"),
    FOLDER("folder");

    private final String value;

    FileTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
