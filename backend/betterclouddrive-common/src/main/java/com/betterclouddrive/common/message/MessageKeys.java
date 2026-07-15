package com.betterclouddrive.common.message;

/** Stable, locale-neutral keys used by API and persisted presentation events. */
public final class MessageKeys {
    public static final String SUCCESS = "common.success";
    public static final String INTERNAL_ERROR = "error.internal";
    public static final String VALIDATION_FAILED = "error.validation";
    public static final String METHOD_NOT_ALLOWED = "error.methodNotAllowed";
    public static final String MALFORMED_BODY = "error.malformedBody";
    public static final String MISSING_PARAMETER = "error.missingParameter";
    public static final String INVALID_PARAMETER = "error.invalidParameter";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_SUCCEEDED = "task.succeeded";
    public static final String TASK_RESULT_EXPIRED = "task.resultExpired";

    private MessageKeys() {
    }

    public static String api(String enumName) {
        return "error." + normalize(enumName);
    }

    public static String notificationTitle(String type) {
        return "notification." + normalize(type) + ".title";
    }

    public static String notificationContent(String type) {
        return "notification." + normalize(type) + ".content";
    }

    public static String taskTitle(String type) {
        return "task." + normalize(type) + ".title";
    }

    public static String activitySummary(String action) {
        return "activity." + normalize(action);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String[] words = value.trim().toLowerCase(java.util.Locale.ROOT).split("_+");
        StringBuilder result = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isEmpty()) {
                result.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
            }
        }
        return result.toString();
    }
}
