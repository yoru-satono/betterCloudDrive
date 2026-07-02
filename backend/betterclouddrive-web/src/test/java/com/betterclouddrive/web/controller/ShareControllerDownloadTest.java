package com.betterclouddrive.web.controller;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.AuthService;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.FolderZipDownloadService;
import com.betterclouddrive.service.ShareService;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShareControllerDownloadTest {

    @Mock private ShareService shareService;
    @Mock private FileService fileService;
    @Mock private AuthService authService;
    @Mock private StorageService storageService;
    @Mock private FolderZipDownloadService folderZipDownloadService;

    private ShareController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ShareController(shareService, fileService, authService, storageService,
                folderZipDownloadService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void downloadSharedFile_shouldReturnPartialContentForValidRange() throws Exception {
        FileEntity file = sharedFile();
        when(shareService.resolveSharedFileDownload("share1234", 7L, "pw"))
                .thenReturn(file);
        when(storageService.downloadObjectRange("objects/test.txt", 2L, 4L))
                .thenReturn(new ByteArrayInputStream("cdef".getBytes()));

        MvcResult result = mockMvc.perform(post("/api/v1/shares/access/share1234/download/7")
                        .header(HttpHeaders.RANGE, "bytes=2-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4))
                .andExpect(content().bytes("cdef".getBytes(StandardCharsets.UTF_8)));
        verify(shareService).recordSharedDownload("share1234", "pw");
        verify(storageService).downloadObjectRange("objects/test.txt", 2L, 4L);
        verify(storageService, never()).downloadObject("objects/test.txt");
    }

    @Test
    void downloadSharedFile_shouldRejectUnsatisfiedRangeWithoutRecordingDownload() throws Exception {
        FileEntity file = sharedFile();
        when(shareService.resolveSharedFileDownload("share1234", 7L, null))
                .thenReturn(file);

        mockMvc.perform(post("/api/v1/shares/access/share1234/download/7")
                        .header(HttpHeaders.RANGE, "bytes=10-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */10"));

        verify(shareService, never()).recordSharedDownload("share1234", null);
        verify(storageService, never()).downloadObjectRange("objects/test.txt", 10L, 1L);
        verify(storageService, never()).downloadObject("objects/test.txt");
    }

    private FileEntity sharedFile() {
        return FileEntity.builder()
                .id(7L)
                .userId(1L)
                .fileName("test.txt")
                .fileType("file")
                .mimeType("text/plain")
                .fileSize(10L)
                .storagePath("objects/test.txt")
                .build();
    }
}
