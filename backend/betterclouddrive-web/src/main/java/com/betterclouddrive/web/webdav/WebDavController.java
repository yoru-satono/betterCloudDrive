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
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.io.InputStream;
import com.betterclouddrive.web.warehouse.WarehouseTransferContext;
import com.betterclouddrive.common.dto.WarehouseEvent.Channel;
import com.betterclouddrive.common.dto.WarehouseEvent.Mode;
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

    @RequestMapping(value = {"", "/", "/**"}, method = RequestMethod.OPTIONS)
    public void options(HttpServletResponse response) {
        handleOptions(response);
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
        long fileSize = file.getFileSize() != null ? file.getFileSize() : 0L;
        long start = 0L;
        long end = Math.max(0L, fileSize - 1);
        String range = request.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring(6).split("-", 2);
            try {
                start = Long.parseLong(bounds[0]);
                if (bounds.length > 1 && !bounds[1].isBlank()) end = Long.parseLong(bounds[1]);
            } catch (NumberFormatException e) {
                response.setHeader("Content-Range", "bytes */" + fileSize);
                response.setStatus(416);
                return;
            }
            if (start < 0 || start >= fileSize || end < start) {
                response.setHeader("Content-Range", "bytes */" + fileSize);
                response.setStatus(416);
                return;
            }
            end = Math.min(end, fileSize - 1);
            response.setStatus(206);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
        }
        long responseLength = fileSize == 0 ? 0 : end - start + 1;
        response.setHeader("Accept-Ranges", "bytes");
        response.setContentLengthLong(responseLength);
        if (file.getMd5Hash() != null) response.setHeader("ETag", "\"" + file.getMd5Hash() + "\"");

        if (!headOnly) {
            WarehouseTransferContext.bind(userId, null, file.getId(), Channel.WEBDAV, Mode.FILE);
            try (InputStream in = storageService.downloadObject(file.getStoragePath())) {
                in.skipNBytes(start);
                in.transferTo(new java.io.OutputStream() {
                    private long remaining = responseLength;
                    @Override public void write(int b) throws IOException {
                        if (remaining-- > 0) response.getOutputStream().write(b);
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        if (remaining <= 0) return;
                        int count = (int) Math.min(len, remaining);
                        response.getOutputStream().write(b, off, count);
                        remaining -= count;
                    }
                });
            }
        }
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    private void handlePut(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Long userId = currentUserId();
        String davPath = extractDavPath(request);
        if (davPath.isEmpty() || davPath.equals("/")) { response.setStatus(405); return; }
        if (rejectLockedWrite(request, response, userId, davPath)) return;

        Long parentId = pathResolver.resolveParentId(userId, davPath);
        String fileName = pathResolver.extractFileName(davPath);
        long contentLength = Math.max(0L, request.getContentLengthLong());
        FileEntity existing = pathResolver.resolve(userId, davPath);

        uploadService.streamUpload(userId, parentId, fileName, contentLength, request.getInputStream(), null);
        if (existing != null) {
            fileService.deleteFiles(userId, List.of(existing.getId()));
        }
        response.setStatus(existing != null ? 204 : 201);
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

        String destHeader = request.getHeader("Destination");
        if (destHeader == null) { response.setStatus(400); return; }
        String destPath = extractDavPathFromUrl(destHeader);
        if (!pathResolver.parentExists(userId, destPath)) { response.setStatus(409); return; }
        Long destParentId = pathResolver.resolveParentId(userId, destPath);
        String destName = pathResolver.extractFileName(destPath);
        if (destName.isBlank()) { response.setStatus(400); return; }

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
        String existing = redisTemplate.opsForValue().get(LOCK_PREFIX + userId + ":" + davPath);
        String supplied = request.getHeader("Lock-Token");
        if (existing == null || supplied == null || !supplied.contains(existing)) {
            response.setStatus(409);
            return;
        }
        redisTemplate.delete(LOCK_PREFIX + userId + ":" + davPath);
        response.setStatus(204);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ((UserPrincipal) principal).getUserId();
    }

    private boolean rejectLockedWrite(HttpServletRequest request, HttpServletResponse response,
                                      Long userId, String davPath) {
        String token = redisTemplate.opsForValue().get(LOCK_PREFIX + userId + ":" + davPath);
        if (token == null) return false;
        String condition = String.valueOf(request.getHeader("If")) + " "
                + String.valueOf(request.getHeader("Lock-Token"));
        if (condition.contains(token)) return false;
        response.setStatus(423);
        return true;
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
