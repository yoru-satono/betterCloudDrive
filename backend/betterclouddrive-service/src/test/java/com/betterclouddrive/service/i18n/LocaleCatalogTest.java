package com.betterclouddrive.service.i18n;

import com.betterclouddrive.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;

import static org.assertj.core.api.Assertions.*;

class LocaleCatalogTest {
    private final LocaleCatalog catalog = new LocaleCatalog("zh-Hans");

    @Test
    void discoversEveryMailBundleLocale() {
        assertThat(catalog.supportedLocales()).containsExactly("en-US", "zh-CN", "zh-Hans", "zh-SG");
    }

    @Test
    void normalizesSupportedLanguageTagsAndUsesTheDefaultForMissingValues() {
        assertThat(catalog.normalize("en-us")).isEqualTo("en-US");
        assertThat(catalog.normalize("zh-cn")).isEqualTo("zh-CN");
        assertThat(catalog.normalize("zh-sg")).isEqualTo("zh-SG");
        assertThat(catalog.bundleLocale("zh-CN")).isEqualTo("zh-Hans");
        assertThat(catalog.bundleLocale("zh-SG")).isEqualTo("zh-Hans");
        assertThat(catalog.normalize(null)).isEqualTo("zh-Hans");
        assertThat(catalog.resolveOrDefault("not-a-supported-locale")).isEqualTo("zh-Hans");
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
