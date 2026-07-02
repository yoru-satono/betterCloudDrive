package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.download.DownloadTicketService;
import com.betterclouddrive.web.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadControllerThumbnailTest {

    @Mock
    private FileService fileService;

    @Mock
    private StorageService storageService;

    @Mock
    private FolderZipDownloadService folderZipDownloadService;

    @Mock
    private DownloadTicketService downloadTicketService;

    private DownloadController controller;
    private final UserPrincipal user = new UserPrincipal(7L, "chain", "jti", "ROLE_USER");

    @BeforeEach
    void setUp() {
        controller = new DownloadController(fileService, storageService, folderZipDownloadService, downloadTicketService);
    }

    @Test
    void thumbnail_shouldStreamGeneratedThumbnail() throws Exception {
        FileEntity file = FileEntity.builder()
                .id(42L)
                .userId(7L)
                .fileName("photo.png")
                .fileType("file")
                .thumbnailPath("thumbnails/users/7/42.jpg")
                .isDeleted(false)
                .build();
        byte[] thumbnail = new byte[]{1, 2, 3};
        when(fileService.getFile(7L, 42L)).thenReturn(file);
        when(storageService.downloadObject("thumbnails/users/7/42.jpg"))
                .thenReturn(new ByteArrayInputStream(thumbnail));

        ResponseEntity<StreamingResponseBody> response = controller.thumbnail(user, 42L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=86400");
        assertThat(output.toByteArray()).isEqualTo(thumbnail);
    }

    @Test
    void thumbnail_shouldRejectFilesWithoutGeneratedThumbnail() {
        FileEntity file = FileEntity.builder()
                .id(42L)
                .userId(7L)
                .fileName("photo.png")
                .fileType("file")
                .isDeleted(false)
                .build();
        when(fileService.getFile(7L, 42L)).thenReturn(file);

        assertThatThrownBy(() -> controller.thumbnail(user, 42L))
                .isInstanceOf(BusinessException.class);

        verify(storageService, never()).downloadObject("thumbnails/users/7/42.jpg");
    }
}
