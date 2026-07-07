package com.betterclouddrive.web.service;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.web.config.OnlyOfficeProperties;
import com.betterclouddrive.web.dto.response.OfficeConfigResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OnlyOfficeService {

    private static final Set<String> WORD_EXTENSIONS = Set.of("doc", "docx", "odt", "rtf");
    private static final Set<String> CELL_EXTENSIONS = Set.of("xls", "xlsx", "ods", "csv");
    private static final Set<String> SLIDE_EXTENSIONS = Set.of("ppt", "pptx", "odp");

    private final OnlyOfficeProperties properties;
    private final RestClient.Builder restClientBuilder;

    public OfficeConfigResponse buildPersonalConfig(Long userId, FileEntity file) {
        return buildConfig(userId, null, file, false);
    }

    public OfficeConfigResponse buildTeamConfig(Long userId, Long teamId, FileEntity file, boolean editable) {
        return buildConfig(userId, teamId, file, editable);
    }

    public boolean isOfficeFile(FileEntity file) {
        return file != null && extension(file.getFileName()).map(this::isOfficeExtension).orElse(false);
    }

    public Long validateContentToken(String token, Long fileId, Long teamId) {
        Claims claims = parseToken(token);
        if (!"content".equals(claims.get("purpose", String.class))) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Invalid office token");
        }
        validateTokenResource(claims, fileId, teamId);
        return numberClaim(claims, "userId");
    }

    public Long validateCallbackToken(String token, Long fileId, Long teamId) {
        return validateCallbackToken(token, fileId, teamId, null);
    }

    public Long validateCallbackToken(String token, Long fileId, Long teamId, String callbackKey) {
        Claims claims = parseToken(token);
        if (!"callback".equals(claims.get("purpose", String.class))) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Invalid office callback token");
        }
        validateTokenResource(claims, fileId, teamId);
        validateCallbackKey(claims, callbackKey);
        return numberClaim(claims, "userId");
    }

    public void validateDocumentServerToken(String token) {
        parseToken(token);
    }

    public byte[] downloadEditedContent(String url) {
        if (url == null || url.isBlank()) {
            throw new BusinessException(ApiCode.BAD_REQUEST, "OnlyOffice callback URL is missing");
        }
        String downloadUrl = resolveDocumentServerDownloadUrl(url);
        return restClientBuilder.build()
                .get()
                .uri(downloadUrl)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BusinessException(ApiCode.DOWNLOAD_FAILED,
                            "Failed to download edited document from OnlyOffice");
                })
                .body(byte[].class);
    }

    public String resolveDocumentServerDownloadUrl(String url) {
        String trimmedUrl = url.trim();
        String publicUrl = properties.normalizedPublicUrl();
        String internalUrl = properties.normalizedInternalUrl();
        if (publicUrl.isBlank() || internalUrl.isBlank()) {
            return trimmedUrl;
        }
        if (trimmedUrl.equals(publicUrl) || trimmedUrl.startsWith(publicUrl + "/")) {
            return internalUrl + trimmedUrl.substring(publicUrl.length());
        }
        try {
            URI source = URI.create(trimmedUrl);
            URI publicUri = URI.create(publicUrl);
            if (sameOrigin(source, publicUri) || sameLocalDocumentServerAlias(source, publicUri)) {
                URI internalUri = URI.create(internalUrl);
                return replaceOrigin(source, internalUri);
            }
        } catch (IllegalArgumentException ignored) {
            return trimmedUrl;
        }
        return trimmedUrl;
    }

    private OfficeConfigResponse buildConfig(Long userId, Long teamId, FileEntity file, boolean editable) {
        String documentType = documentType(file);
        String documentKey = documentKey(file);
        String contentToken = createResourceToken("content", userId, file.getId(), teamId, documentKey);
        String callbackToken = teamId != null && editable
                ? createResourceToken("callback", userId, file.getId(), teamId, documentKey)
                : null;
        String contentUrl = resourceUrl(teamId, file.getId(), "content", contentToken);
        String callbackUrl = callbackToken == null ? null : resourceUrl(teamId, file.getId(), "callback", callbackToken);

        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("edit", editable);
        permissions.put("review", editable);
        permissions.put("comment", editable);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", extension(file.getFileName()).orElseThrow());
        document.put("key", documentKey);
        document.put("title", file.getFileName());
        document.put("url", contentUrl);
        document.put("permissions", permissions);

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", editable ? "edit" : "view");
        editorConfig.put("lang", "zh-CN");
        editorConfig.put("user", Map.of(
                "id", String.valueOf(userId),
                "name", "user-" + userId
        ));
        Map<String, Object> customization = new LinkedHashMap<>();
        customization.put("plugins", false);
        customization.put("macros", false);
        customization.put("macrosMode", "disable");
        editorConfig.put("customization", customization);
        if (callbackUrl != null) {
            editorConfig.put("callbackUrl", callbackUrl);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "desktop");
        config.put("documentType", documentType);
        config.put("document", document);
        config.put("editorConfig", editorConfig);

        String onlyOfficeToken = createOnlyOfficeConfigToken(config);
        config.put("token", onlyOfficeToken);

        return OfficeConfigResponse.builder()
                .documentServerUrl(properties.normalizedPublicUrl())
                .editable(editable)
                .config(config)
                .build();
    }

    private String resourceUrl(Long teamId, Long fileId, String kind, String token) {
        String prefix = properties.normalizedAppInternalApiBaseUrl();
        if (teamId == null) {
            return prefix + "/office/files/" + fileId + "/" + kind + "?token=" + token;
        }
        return prefix + "/teams/" + teamId + "/office/files/" + fileId + "/" + kind + "?token=" + token;
    }

    private String documentType(FileEntity file) {
        String ext = extension(file.getFileName()).orElseThrow(() ->
                new BusinessException(ApiCode.BAD_REQUEST, "Unsupported office file type"));
        if (WORD_EXTENSIONS.contains(ext)) {
            return "word";
        }
        if (CELL_EXTENSIONS.contains(ext)) {
            return "cell";
        }
        if (SLIDE_EXTENSIONS.contains(ext)) {
            return "slide";
        }
        throw new BusinessException(ApiCode.BAD_REQUEST, "Unsupported office file type");
    }

    private boolean isOfficeExtension(String ext) {
        return WORD_EXTENSIONS.contains(ext) || CELL_EXTENSIONS.contains(ext) || SLIDE_EXTENSIONS.contains(ext);
    }

    private Optional<String> extension(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String documentKey(FileEntity file) {
        String raw = file.getId() + ":" + file.getUpdatedAt() + ":" + file.getVersionCount() + ":" + file.getMd5Hash();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String createResourceToken(String purpose, Long userId, Long fileId, Long teamId, String documentKey) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(fileId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getTokenTtlSeconds())))
                .claim("purpose", purpose)
                .claim("userId", userId)
                .claim("fileId", fileId)
                .claim("teamId", teamId)
                .claim("documentKey", documentKey)
                .signWith(signingKey())
                .compact();
    }

    private String createOnlyOfficeConfigToken(Map<String, Object> config) {
        return Jwts.builder()
                .claims(config)
                .signWith(signingKey())
                .compact();
    }

    private Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Office token is missing");
        }
        try {
            return Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Invalid office token");
        }
    }

    private void validateTokenResource(Claims claims, Long fileId, Long teamId) {
        Long tokenFileId = numberClaim(claims, "fileId");
        Long tokenTeamId = numberClaim(claims, "teamId");
        if (!Objects.equals(tokenFileId, fileId) || !Objects.equals(tokenTeamId, teamId)) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Office token resource mismatch");
        }
    }

    private void validateCallbackKey(Claims claims, String callbackKey) {
        String tokenDocumentKey = claims.get("documentKey", String.class);
        if (tokenDocumentKey == null || tokenDocumentKey.isBlank()) {
            return;
        }
        if (callbackKey == null || callbackKey.isBlank() || !tokenDocumentKey.equals(callbackKey.trim())) {
            throw new BusinessException(ApiCode.FORBIDDEN, "Office callback key mismatch");
        }
    }

    private Long numberClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private SecretKey signingKey() {
        byte[] bytes = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("ONLYOFFICE_JWT_SECRET must be at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    private boolean sameOrigin(URI left, URI right) {
        return equalsIgnoreCase(left.getScheme(), right.getScheme())
                && equalsIgnoreCase(left.getHost(), right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private boolean sameLocalDocumentServerAlias(URI source, URI publicUri) {
        return equalsIgnoreCase(source.getScheme(), publicUri.getScheme())
                && effectivePort(source) == effectivePort(publicUri)
                && isLocalDocumentServerAlias(source.getHost())
                && isLocalDocumentServerAlias(publicUri.getHost());
    }

    private boolean isLocalDocumentServerAlias(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("::1")
                || normalized.equals("10.0.2.2")
                || normalized.equals("host.docker.internal");
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private String replaceOrigin(URI source, URI replacement) {
        StringBuilder builder = new StringBuilder();
        builder.append(replacement.getScheme()).append("://").append(replacement.getRawAuthority());
        if (source.getRawPath() != null) {
            builder.append(source.getRawPath());
        }
        if (source.getRawQuery() != null) {
            builder.append('?').append(source.getRawQuery());
        }
        if (source.getRawFragment() != null) {
            builder.append('#').append(source.getRawFragment());
        }
        return builder.toString();
    }
}
