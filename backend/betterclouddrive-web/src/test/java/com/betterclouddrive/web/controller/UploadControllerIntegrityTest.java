package com.betterclouddrive.web.controller;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.dto.UploadInitResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.web.exception.GlobalExceptionHandler;
import com.betterclouddrive.web.security.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadControllerIntegrityTest {
    private UploadService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        service = mock(UploadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(service))
                .setCustomArgumentResolvers(new CurrentUserResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(1L, "test", "id", "ROLE_USER"), null));
    }

    @AfterEach void clearUser() { SecurityContextHolder.clearContext(); }

    @Test void missingAndMalformedFileDigestAreParameterErrors() throws Exception {
        for (String digest : new String[] {"null", "\"bad\"", "\"\""}) {
            mvc.perform(post("/api/v1/upload/init").contentType("application/json")
                    .content("{\"fileName\":\"hello\",\"fileSize\":5,\"md5Hash\":" + digest + "}"))
                    .andExpect(status().isBadRequest()).andExpect(result -> assertThat(new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(400));
        }
        verifyNoInteractions(service);
    }

    @Test void missingChunkDigestIsAParameterError() throws Exception {
        mvc.perform(multipart("/api/v1/upload/session/chunk")
                .file(new MockMultipartFile("file", "part", "application/octet-stream", "hello".getBytes()))
                .param("chunkNumber", "0"))
                .andExpect(status().isBadRequest()).andExpect(result -> assertThat(new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(400));
        verifyNoInteractions(service);
    }

    @Test void chunkBusinessErrorIsNotWrappedAsInternalError() throws Exception {
        doThrow(new BusinessException(ApiCode.CHUNK_MD5_MISMATCH)).when(service)
                .uploadChunk(eq("session"), eq(1L), eq(0), any(), anyString());
        mvc.perform(multipart("/api/v1/upload/session/chunk")
                .file(new MockMultipartFile("file", "part", "application/octet-stream", "hello".getBytes()))
                .param("chunkNumber", "0").param("chunkMd5", "a".repeat(32)))
                .andExpect(status().isOk()).andExpect(result -> assertThat(new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(419007))
                .andExpect(result -> assertThat(new tools.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString()).get("messageKey").asText()).isEqualTo("error.chunkMd5Mismatch"));
    }
    @Test void initializationSerializesOnlyTheSelectedBranch() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        when(service.initUpload(eq(1L), isNull(), eq("hello"), eq(5L), anyString()))
                .thenReturn(UploadInitResult.instant(9L), UploadInitResult.multipart("s", 5242880, 1));
        String json = "{\"fileName\":\"hello\",\"fileSize\":5,\"md5Hash\":\"" + "a".repeat(32) + "\"}";
        var instant = mvc.perform(post("/api/v1/upload/init").contentType("application/json").content(json)).andReturn();
        var first = mapper.readTree(instant.getResponse().getContentAsString()).get("data");
        assertThat(first.get("mode").asText()).isEqualTo("INSTANT");
        assertThat(first.get("fileId").asLong()).isEqualTo(9L);
        assertThat(first.size()).isEqualTo(2);
        var multipart = mvc.perform(post("/api/v1/upload/init").contentType("application/json").content(json)).andReturn();
        var second = mapper.readTree(multipart.getResponse().getContentAsString()).get("data");
        assertThat(second.get("mode").asText()).isEqualTo("MULTIPART");
        assertThat(second.size()).isEqualTo(4);
        assertThat(second.has("fileId")).isFalse();
    }

    @Test void removedInstantRouteReturns404() throws Exception {
        mvc.perform(post("/api/v1/upload/instant").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(new tools.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(404));
        verifyNoInteractions(service);
    }

}
