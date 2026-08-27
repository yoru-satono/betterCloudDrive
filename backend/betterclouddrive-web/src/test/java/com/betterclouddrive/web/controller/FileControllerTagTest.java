package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.TagEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.TagService;
import com.betterclouddrive.web.dto.request.ReplaceFileTagsRequest;
import com.betterclouddrive.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileControllerTagTest {

    private final FileService fileService = mock(FileService.class);
    private final TagService tagService = mock(TagService.class);
    private final FileController controller = new FileController(fileService, tagService);
    private final UserPrincipal user = new UserPrincipal(7L, "tester", "jti", "USER");

    @Test
    void listFileTagsUsesPersonalUserAndFile() {
        TagEntity tag = TagEntity.builder().id(3L).userId(7L).tagName("work").build();
        when(tagService.listFileTags(7L, 9L)).thenReturn(List.of(tag));

        var response = controller.listFileTags(user, 9L);

        assertThat(response.getData()).containsExactly(tag);
        verify(tagService).listFileTags(7L, 9L);
    }

    @Test
    void replaceFileTagsDelegatesOneAtomicSet() {
        ReplaceFileTagsRequest request = new ReplaceFileTagsRequest();
        request.setTagIds(List.of(3L, 4L, 3L));
        when(tagService.replaceFileTags(7L, 9L, request.getTagIds())).thenReturn(List.of());

        controller.replaceFileTags(user, 9L, request);

        verify(tagService).replaceFileTags(7L, 9L, List.of(3L, 4L, 3L));
    }
}
