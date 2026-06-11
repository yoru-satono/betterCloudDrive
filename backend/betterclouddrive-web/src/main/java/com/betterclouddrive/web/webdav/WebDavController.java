package com.betterclouddrive.web.webdav;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.service.FileService;
import com.betterclouddrive.service.UploadService;
import com.betterclouddrive.storage.StorageService;
import com.betterclouddrive.web.security.UserPrincipal;
import com.betterclouddrive.web.webdav.PropFindXmlBuilder.DavResource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Controller
@RequestMapping("/webdav")
@RequiredArgsConstructor
public class WebDavController {

    private final FileService fileService;
    private final UploadService uploadService;
    private final StorageService storageService;
    private final WebDavPathResolver pathResolver;
    private final PropFindXmlBuilder xmlBuilder;
    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_PREFIX = "webdav:lock:";

    @RequestMapping({"", "/", "/**"})
    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod().toUpperCase();
        try {
            switch (method) {
                case "OPTIONS"  -> handleOptions(response);
                case "PROPFIND" -> handlePropFind(request, response);
                case "GET"      -> handleGet(request, response, false);
                case "HEAD"     -> handleGet(request, response, true);
                case "PUT"      -> handlePut(request, response);
                case "MKCOL"    -> handleMkCol(request, response);
                case "DELETE"   -> handleDelete(request, response);
                case "MOVE"     -> handleMove(request, response);
                case "COPY"     -> handleCopy(request, response);
                case "LOCK"     -> handleLock(request, response);
                case "UNLOCK"   -> handleUnlock(request, response);
                default         -> response.setStatus(405);
            }
        } catch (BusinessException e) {
            log.debug("WebDAV BusinessException: code={} msg={}", e.getCode(), e.getMessage());
            response.setStatus(409);
        } catch (Exception e) {
            log.error("WebDAV error: method={} path={}", method, request.getServletPath(), e);
            response.setStatus(500);
        }
    }

    // ── OPTIONS ───────────────────────────────────────────────────────────────

    private void handleOptions(HttpServletResponse response) {
        response.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, LOCK, UNLOCK");
        response.setHeader("DAV", "1, 2");
        response.setStatus(200);
    }

    // ── PROPFIND ──────────────────────────────────────────────────────────────

    private void handlePropFind(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);

        int depth = 1;
        String depthHeader = request.getHeader("Depth");
        if ("0".equals(depthHeader)) depth = 0;

        List<DavResource> resources = new ArrayList<>();

        if (davPath.isEmpty() || davPath.equals("/")) {
            FileEntity pseudoRoot = FileEntity.builder()
                    .fileName("/").fileType("folder").fileSize(0L)
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            resources.add(new DavResource("/webdav/", pseudoRoot));
            if (depth >= 1) {
                PageResult<FileEntity> children = fileService.listFiles(userId, null, 1, 1000, "fileName", "asc");
                for (FileEntity child : children.getRecords()) {
                    String href = "/webdav/" + encodeName(child.getFileName())
                            + ("folder".equals(child.getFileType()) ? "/" : "");
                    resources.add(new DavResource(href, child));
                }
            }
        } else {
            FileEntity target = pathResolver.resolve(userId, davPath);
            if (target == null) { response.setStatus(404); return; }

            String selfHref = "/webdav" + normalizePath(davPath)
                    + ("folder".equals(target.getFileType()) ? "/" : "");
            resources.add(new DavResource(selfHref, target));

            if (depth >= 1 && "folder".equals(target.getFileType())) {
                PageResult<FileEntity> children =
                        fileService.listFiles(userId, target.getId(), 1, 1000, "fileName", "asc");
                for (FileEntity child : children.getRecords()) {
                    String childHref = selfHref + encodeName(child.getFileName())
                            + ("folder".equals(child.getFileType()) ? "/" : "");
                    resources.add(new DavResource(childHref, child));
                }
            }
        }

        String xml = xmlBuilder.build(resources);
        response.setStatus(207);
        response.setContentType("application/xml; charset=utf-8");
        response.getWriter().write(xml);
    }

    // ── GET / HEAD ────────────────────────────────────────────────────────────

    private void handleGet(HttpServletRequest request, HttpServletResponse response, boolean headOnly)
            throws IOException {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);

        if (davPath.isEmpty() || davPath.equals("/")) { response.setStatus(405); return; }

        FileEntity file = pathResolver.resolve(userId, davPath);
        if (file == null) { response.setStatus(404); return; }
        if ("folder".equals(file.getFileType())) { response.setStatus(405); return; }

        response.setContentType(file.getMimeType() != null ? file.getMimeType() : "application/octet-stream");
        response.setContentLengthLong(file.getFileSize() != null ? file.getFileSize() : 0L);
        if (file.getMd5Hash() != null) response.setHeader("ETag", "\"" + file.getMd5Hash() + "\"");

        if (!headOnly) {
            try (InputStream in = storageService.downloadObject(file.getStoragePath())) {
                in.transferTo(response.getOutputStream());
            }
        }
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    private void handlePut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);
        if (davPath.isEmpty() || davPath.equals("/")) { response.setStatus(405); return; }

        Long parentId = pathResolver.resolveParentId(userId, davPath);
        String fileName = pathResolver.extractFileName(davPath);
        long contentLength = Math.max(0L, request.getContentLengthLong());
        boolean exists = pathResolver.resolve(userId, davPath) != null;

        uploadService.streamUpload(userId, parentId, fileName, contentLength, request.getInputStream(), null);
        response.setStatus(exists ? 204 : 201);
    }

    // ── MKCOL ─────────────────────────────────────────────────────────────────

    private void handleMkCol(HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);
        if (davPath.isEmpty() || davPath.equals("/")) { response.setStatus(405); return; }

        Long parentId = pathResolver.resolveParentId(userId, davPath);
        String folderName = pathResolver.extractFileName(davPath);

        try {
            fileService.createFolder(userId, parentId, folderName);
            response.setStatus(201);
        } catch (BusinessException e) {
            response.setStatus(e.getCode() == 409001 ? 405 : 409);
        }
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);

        FileEntity file = pathResolver.resolve(userId, davPath);
        if (file == null) { response.setStatus(404); return; }

        fileService.deleteFiles(userId, List.of(file.getId()));
        response.setStatus(204);
    }

    // ── MOVE ──────────────────────────────────────────────────────────────────

    private void handleMove(HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);

        FileEntity file = pathResolver.resolve(userId, davPath);
        if (file == null) { response.setStatus(404); return; }

        String destHeader = request.getHeader("Destination");
        if (destHeader == null) { response.setStatus(400); return; }
        String destPath = extractDavPathFromUrl(destHeader);
        Long destParentId = pathResolver.resolveParentId(userId, destPath);
        String destName = pathResolver.extractFileName(destPath);

        boolean parentChanged = !Objects.equals(destParentId, file.getParentId());
        boolean nameChanged = !destName.equals(file.getFileName());

        if (parentChanged) fileService.moveFile(userId, file.getId(), destParentId);
        if (nameChanged) fileService.renameFile(userId, file.getId(), destName);

        response.setStatus(201);
    }

    // ── COPY ──────────────────────────────────────────────────────────────────

    private void handleCopy(HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);

        FileEntity file = pathResolver.resolve(userId, davPath);
        if (file == null) { response.setStatus(404); return; }
        if ("folder".equals(file.getFileType())) { response.setStatus(403); return; }

        String destHeader = request.getHeader("Destination");
        if (destHeader == null) { response.setStatus(400); return; }
        String destPath = extractDavPathFromUrl(destHeader);
        Long destParentId = pathResolver.resolveParentId(userId, destPath);
        String destName = pathResolver.extractFileName(destPath);

        fileService.copyFileTo(userId, file.getId(), destParentId, destName);
        response.setStatus(201);
    }

    // ── LOCK ──────────────────────────────────────────────────────────────────

    private void handleLock(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);
        String lockToken = UUID.randomUUID().toString();

        redisTemplate.opsForValue().set(LOCK_PREFIX + userId + ":" + davPath, lockToken, 3600, TimeUnit.SECONDS);

        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<D:prop xmlns:D=\"DAV:\">\n" +
                "  <D:lockdiscovery>\n" +
                "    <D:activelock>\n" +
                "      <D:locktype><D:write/></D:locktype>\n" +
                "      <D:lockscope><D:exclusive/></D:lockscope>\n" +
                "      <D:depth>0</D:depth>\n" +
                "      <D:timeout>Second-3600</D:timeout>\n" +
                "      <D:locktoken>\n" +
                "        <D:href>opaquelocktoken:" + lockToken + "</D:href>\n" +
                "      </D:locktoken>\n" +
                "    </D:activelock>\n" +
                "  </D:lockdiscovery>\n" +
                "</D:prop>";

        response.setStatus(200);
        response.setHeader("Lock-Token", "<opaquelocktoken:" + lockToken + ">");
        response.setContentType("application/xml; charset=utf-8");
        response.getWriter().write(xml);
    }

    // ── UNLOCK ────────────────────────────────────────────────────────────────

    private void handleUnlock(HttpServletRequest request, HttpServletResponse response) {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);
        redisTemplate.delete(LOCK_PREFIX + userId + ":" + davPath);
        response.setStatus(204);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ((UserPrincipal) principal).getUserId();
    }

    private String extractDavPath(HttpServletRequest request) {
        String path = request.getServletPath();
        String dav = path.startsWith("/webdav") ? path.substring(7) : path;
        if (dav.endsWith("/") && dav.length() > 1) dav = dav.substring(0, dav.length() - 1);
        return dav;
    }

    private String extractDavPathFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            String dav = path.startsWith("/webdav") ? path.substring(7) : path;
            if (dav.endsWith("/") && dav.length() > 1) dav = dav.substring(0, dav.length() - 1);
            return URLDecoder.decode(dav, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return url.startsWith("/webdav") ? url.substring(7) : url;
        }
    }

    private String normalizePath(String davPath) {
        return davPath.startsWith("/") ? davPath : "/" + davPath;
    }

    private String encodeName(String name) {
        return name.replace(" ", "%20").replace("#", "%23").replace("&", "%26");
    }
}
