package com.betterclouddrive.web.controller;

import com.betterclouddrive.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.format.annotation.DateTimeFormat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class TeamControllerContractTest {

    @Test
    void listAuditLogs_shouldUseIsoDateTimeForTimeFilters() throws NoSuchMethodException {
        Method method = TeamController.class.getMethod(
                "listAuditLogs",
                UserPrincipal.class,
                Long.class,
                Long.class,
                String.class,
                String.class,
                Integer.class,
                LocalDateTime.class,
                LocalDateTime.class,
                int.class,
                int.class);

        assertIsoDateTime(method.getParameters()[6], "startAt");
        assertIsoDateTime(method.getParameters()[7], "endAt");
    }

    @Test
    void exportAuditLogs_shouldUseIsoDateTimeForTimeFilters() throws NoSuchMethodException {
        Method method = TeamController.class.getMethod(
                "exportAuditLogs",
                UserPrincipal.class,
                Long.class,
                Long.class,
                String.class,
                String.class,
                Integer.class,
                LocalDateTime.class,
                LocalDateTime.class);

        assertIsoDateTime(method.getParameters()[6], "startAt");
        assertIsoDateTime(method.getParameters()[7], "endAt");
    }

    @Test
    void queueAuditExport_shouldUseIsoDateTimeForTimeFilters() throws NoSuchMethodException {
        Method method = TeamController.class.getMethod(
                "queueAuditExport",
                UserPrincipal.class,
                Long.class,
                Long.class,
                String.class,
                String.class,
                Integer.class,
                LocalDateTime.class,
                LocalDateTime.class);

        assertIsoDateTime(method.getParameters()[6], "startAt");
        assertIsoDateTime(method.getParameters()[7], "endAt");
    }

    private void assertIsoDateTime(Parameter parameter, String name) {
        DateTimeFormat dateTimeFormat = findAnnotation(parameter, DateTimeFormat.class);

        assertThat(dateTimeFormat)
                .as(name + " DateTimeFormat")
                .isNotNull();
        assertThat(dateTimeFormat.iso()).isEqualTo(DateTimeFormat.ISO.DATE_TIME);
    }

    private <T extends Annotation> T findAnnotation(Parameter parameter, Class<T> type) {
        return Arrays.stream(parameter.getAnnotations())
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }
}
