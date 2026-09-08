package com.betterclouddrive.web.controller;

import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.dto.request.ReplaceFileTagsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class TeamControllerContractTest {

    @Test
    void teamFileTags_shouldExposeAtomicGetAndPutMappings() throws NoSuchMethodException {
        Method list = TeamController.class.getMethod(
                "listTeamFileTags", UserPrincipal.class, Long.class, Long.class);
        Method replace = TeamController.class.getMethod(
                "replaceTeamFileTags", UserPrincipal.class, Long.class, Long.class, ReplaceFileTagsRequest.class);

        assertThat(list.getAnnotation(GetMapping.class).value())
                .containsExactly("/{teamId}/files/{fileId}/tags");
        assertThat(replace.getAnnotation(PutMapping.class).value())
                .containsExactly("/{teamId}/files/{fileId}/tags");
    }

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
