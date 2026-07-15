package com.betterclouddrive.service.mail;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

@Component
public class MailTemplateRenderer {

    private static final String BUNDLE = "mail.messages";
    private final TemplateEngine htmlEngine = engine("mail/templates/", ".html", TemplateMode.HTML);
    private final TemplateEngine textEngine = engine("mail/templates/", ".txt", TemplateMode.TEXT);

    public RenderedEmail render(String messageKey, String localeTag, Map<String, Object> variables) {
        messageKey = semanticKey(messageKey);
        Locale locale = normalizeLocale(localeTag);
        ResourceBundle messages = ResourceBundle.getBundle(BUNDLE, locale);
        Map<String, Object> model = new LinkedHashMap<>();
        if (variables != null) model.putAll(variables);
        localizeRole(messages, model);
        model.put("lang", locale.toLanguageTag());
        model.put("subject", format(message(messages, messageKey + ".subject"), model));
        model.put("preheader", format(message(messages, messageKey + ".preheader"), model));
        model.put("heading", format(message(messages, messageKey + ".heading"), model));
        model.put("body", format(message(messages, messageKey + ".body"), model));
        model.put("buttonLabel", format(optional(messages, messageKey + ".button"), model));
        model.put("codeLabel", optional(messages, messageKey + ".codeLabel"));
        model.put("detailsLabel", optional(messages, messageKey + ".detailsLabel"));
        model.put("footer", message(messages, "common.footer"));
        model.put("fallback", message(messages, "common.fallback"));
        model.put("details", localizedDetails(messages, messageKey, model.get("details")));

        Context context = new Context(locale, model);
        return new RenderedEmail(
                (String) model.get("subject"),
                textEngine.process("message", context),
                htmlEngine.process("message", context));
    }

    public String notificationKey(String eventType) {
        if (eventType == null || eventType.isBlank()) return "notification.generic";
        String normalized = eventType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "team_invite", "team_invite_accept", "team_invite_reject", "team_quota_alert",
                 "team_archive", "team_unarchive", "team_restore", "team_permission_update",
                 "team_delete", "team_member_add", "team_role_update", "team_member_remove",
                 "team_owner_transfer", "team_office_save_failed" -> "notification." + semanticKey(normalized);
            default -> "notification.generic";
        };
    }

    private static String semanticKey(String value) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                capitalize = true;
            } else if (capitalize) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static TemplateEngine engine(String prefix, String suffix, TemplateMode mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static Locale normalizeLocale(String localeTag) {
        return "en-US".equals(localeTag) ? Locale.forLanguageTag("en-US") : Locale.forLanguageTag("zh-CN");
    }

    private static String message(ResourceBundle bundle, String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException exception) {
            throw new IllegalArgumentException("Missing mail message key: " + key, exception);
        }
    }

    private static String optional(ResourceBundle bundle, String key) {
        return bundle.containsKey(key) ? bundle.getString(key) : "";
    }

    private static String format(String value, Map<String, Object> variables) {
        String result = value;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private static Map<String, Object> localizedDetails(ResourceBundle bundle, String key, Object rawDetails) {
        if (!(rawDetails instanceof Map<?, ?> details) || details.isEmpty()) return Map.of();
        Map<String, Object> localized = new LinkedHashMap<>();
        details.forEach((name, value) -> {
            String labelKey = key + ".detail." + name;
            String label = bundle.containsKey(labelKey) ? bundle.getString(labelKey) : String.valueOf(name);
            localized.put(label, value);
        });
        return localized;
    }

    private static void localizeRole(ResourceBundle bundle, Map<String, Object> model) {
        Object role = model.get("role");
        if (role == null) return;
        String key = "common.role." + role;
        if (!bundle.containsKey(key)) return;
        String localized = bundle.getString(key);
        model.put("role", localized);
        Object rawDetails = model.get("details");
        if (rawDetails instanceof Map<?, ?> details && details.containsKey("role")) {
            Map<Object, Object> localizedDetails = new LinkedHashMap<>(details);
            localizedDetails.put("role", localized);
            model.put("details", localizedDetails);
        }
    }
}
