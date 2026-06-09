package com.betterclouddrive.common.enums;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FileTypeEnumTest {

    @Test
    void shouldHaveFileValue() {
        assertThat(FileTypeEnum.FILE.getValue()).isEqualTo("file");
    }

    @Test
    void shouldHaveFolderValue() {
        assertThat(FileTypeEnum.FOLDER.getValue()).isEqualTo("folder");
    }
}
