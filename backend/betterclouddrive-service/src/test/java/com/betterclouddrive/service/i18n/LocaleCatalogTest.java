package com.betterclouddrive.service.i18n;

import com.betterclouddrive.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;

import static org.assertj.core.api.Assertions.*;

class LocaleCatalogTest {
    private final LocaleCatalog catalog = new LocaleCatalog("zh-CN");

    @Test
    void discoversEveryMailBundleLocale() {
        assertThat(catalog.supportedLocales()).containsExactly("en-US", "zh-CN");
    }

    @Test
    void normalizesSupportedLanguageTagsAndUsesTheDefaultForMissingValues() {
        assertThat(catalog.normalize("en-us")).isEqualTo("en-US");
        assertThat(catalog.normalize(null)).isEqualTo("zh-CN");
        assertThat(catalog.resolveOrDefault("not-a-supported-locale")).isEqualTo("zh-CN");
    }

    @Test
    void rejectsUnsupportedLocales() {
        assertThatThrownBy(() -> catalog.normalize("fr-FR"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.normalize("not a locale"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAMissingDefaultLocaleAtStartup() {
        assertThatThrownBy(() -> new LocaleCatalog("fr-FR"))
                .isInstanceOf(BeanInitializationException.class)
                .hasMessageContaining("fr-FR");
    }
}
