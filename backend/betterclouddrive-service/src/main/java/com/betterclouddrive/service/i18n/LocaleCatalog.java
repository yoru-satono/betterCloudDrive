package com.betterclouddrive.service.i18n;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;

@Component
public class LocaleCatalog {
    private static final String RESOURCE_PATTERN = "classpath*:mail/messages_*.properties";
    private static final String PREFIX = "messages_";
    private static final String SUFFIX = ".properties";

    private final String defaultLocale;
    private final Set<String> supportedLocales;

    public LocaleCatalog(@Value("${drive.i18n.default-locale:zh-Hans}") String defaultLocale) {
        this.supportedLocales = discover();
        this.defaultLocale = canonicalTag(defaultLocale);
        if (!supportedLocales.contains(this.defaultLocale)) {
            throw new BeanInitializationException("Default locale has no mail resource bundle: " + this.defaultLocale);
        }
    }

    public String normalize(String localeTag) {
        if (!StringUtils.hasText(localeTag)) return defaultLocale;
        try {
            String canonical = canonicalTag(localeTag);
            if (supportedLocales.contains(canonical)) return canonical;
        } catch (IllegalArgumentException ignored) {
            // Invalid and unsupported tags share the same public validation error.
        }
        throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported locale");
    }

    public String resolveOrDefault(String localeTag) {
        if (!StringUtils.hasText(localeTag)) return defaultLocale;
        try {
            String canonical = canonicalTag(localeTag);
            return supportedLocales.contains(canonical) ? canonical : defaultLocale;
        } catch (IllegalArgumentException ignored) {
            return defaultLocale;
        }
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public Set<String> supportedLocales() {
        return supportedLocales;
    }

    private Set<String> discover() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
            TreeSet<String> locales = new TreeSet<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.startsWith(PREFIX) || !filename.endsWith(SUFFIX)) continue;
                String bundleSuffix = filename.substring(PREFIX.length(), filename.length() - SUFFIX.length());
                locales.add(canonicalTag(bundleSuffix.replace('_', '-')));
            }
            if (locales.isEmpty()) throw new BeanInitializationException("No mail locale resources found");
            return Collections.unmodifiableSet(locales);
        } catch (IOException exception) {
            throw new BeanInitializationException("Failed to discover mail locale resources", exception);
        }
    }

    private String canonicalTag(String localeTag) {
        String normalized = localeTag == null ? "" : localeTag.trim().replace('_', '-');
        Locale locale = Locale.forLanguageTag(normalized);
        String canonical = locale.toLanguageTag();
        if (!StringUtils.hasText(normalized) || "und".equals(canonical) || canonical.length() > 35) {
            throw new IllegalArgumentException("Invalid locale tag: " + localeTag);
        }
        return canonical;
    }
}
