package com.betterclouddrive.common.message;

/** Stable, locale-neutral keys used by API and persisted presentation events. */
public final class MessageKeys {
    public static final String SUCCESS = "common.success";
    public static final String INTERNAL_ERROR = "error.internal";
    public static final String VALIDATION_FAILED = "error.validation";
    public static final String METHOD_NOT_ALLOWED = "error.method_not_allowed";
    public static final String MALFORMED_BODY = "error.malformed_body";
    public static final String MISSING_PARAMETER = "error.missing_parameter";
    public static final String INVALID_PARAMETER = "error.invalid_parameter";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_SUCCEEDED = "task.succeeded";
    public static final String TASK_RESULT_EXPIRED = "task.result_expired";

    private MessageKeys() {
    }

    public static String api(String enumName) {
        return "error." + enumName.toLowerCase(java.util.Locale.ROOT);
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
        return value == null ? "unknown" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
