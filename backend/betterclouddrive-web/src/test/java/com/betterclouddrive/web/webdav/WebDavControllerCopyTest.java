package com.betterclouddrive.web.webdav;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebDavControllerCopyTest {

    @Mock private FileService fileService;
    @Mock private UploadService uploadService;
    @Mock private StorageService storageService;
    @Mock private WebDavPathResolver pathResolver;
    @Mock private PropFindXmlBuilder xmlBuilder;
    @Mock private StringRedisTemplate redisTemplate;

    private WebDavController controller;

    @BeforeEach
    void setUp() {
        controller = new WebDavController(fileService, uploadService, storageService, pathResolver,
                xmlBuilder, redisTemplate);
        UserPrincipal principal = new UserPrincipal(7L, "chain", "jti", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void copy_shouldAllowFolderCopyThroughFileService() throws Exception {
        FileEntity folder = FileEntity.builder()
                .id(5L)
                .userId(7L)
                .fileName("Docs")
                .fileType("folder")
                .isDeleted(false)
                .build();
        when(pathResolver.resolve(7L, "/Docs")).thenReturn(folder);
        when(pathResolver.parentExists(7L, "/Archive/Docs")).thenReturn(true);
        when(pathResolver.resolveParentId(7L, "/Archive/Docs")).thenReturn(11L);
        when(pathResolver.extractFileName("/Archive/Docs")).thenReturn("Docs");

        MockHttpServletResponse response = dispatchCopy("/webdav/Docs",
                "http://localhost/webdav/Archive/Docs");

        assertThat(response.getStatus()).isEqualTo(201);
        verify(fileService).copyFileTo(7L, 5L, 11L, "Docs");
    }

    @Test
    void copy_shouldRejectMissingDestinationParent() throws Exception {
        FileEntity folder = FileEntity.builder()
                .id(5L)
                .userId(7L)
                .fileName("Docs")
                .fileType("folder")
                .isDeleted(false)
                .build();
        when(pathResolver.resolve(7L, "/Docs")).thenReturn(folder);
        when(pathResolver.parentExists(7L, "/Missing/Docs")).thenReturn(false);

        MockHttpServletResponse response = dispatchCopy("/webdav/Docs",
                "http://localhost/webdav/Missing/Docs");

        assertThat(response.getStatus()).isEqualTo(409);
        verifyNoInteractions(fileService);
    }

    private MockHttpServletResponse dispatchCopy(String servletPath, String destination) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("COPY", servletPath);
        request.setServletPath(servletPath);
        request.addHeader("Destination", destination);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.dispatch(request, response);

        return response;
    }
}
