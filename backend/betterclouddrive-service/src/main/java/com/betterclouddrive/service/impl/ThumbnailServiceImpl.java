package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.service.ThumbnailService;
import com.betterclouddrive.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailServiceImpl implements ThumbnailService {

    private static final int MAX_THUMBNAIL_SIDE = 320;
    private static final String THUMBNAIL_CONTENT_TYPE = "image/jpeg";

    private final FileRepository fileRepository;
    private final StorageService storageService;
    private final TeamTaskService teamTaskService;

    @Async("thumbnailExecutor")
    @Transactional
    @Override
    public void generateThumbnailAsync(Long fileId) {
        generate(fileId);
    }

    @Transactional
    @Override
    public void generateThumbnail(Long fileId) {
        generate(fileId);
    }

    private void generate(Long fileId) {
        if (fileId == null) {
            return;
        }
        FileEntity file = fileRepository.findById(fileId).orElse(null);
        if (!canGenerate(file)) {
            return;
        }

        TeamTaskEntity task = file.getTeamId() == null ? null : teamTaskService.startTask(
                file.getTeamId(), null, "THUMBNAIL", "FILE", file.getId(), "生成团队文件缩略图");
        try (InputStream inputStream = storageService.downloadObject(file.getStoragePath())) {
            BufferedImage source = ImageIO.read(inputStream);
            if (source == null) {
                log.warn("Thumbnail generation skipped because image cannot be decoded, fileId={}", fileId);
                markTaskFailed(task, "图片无法解码");
                return;
            }

            BufferedImage thumbnail = resize(source);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(thumbnail, "jpg", output)) {
                log.warn("Thumbnail generation skipped because JPEG writer is unavailable, fileId={}", fileId);
                markTaskFailed(task, "JPEG writer is unavailable");
                return;
            }

            byte[] bytes = output.toByteArray();
            String thumbnailPath = thumbnailPath(file);
            storageService.uploadObject(
                    thumbnailPath,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    THUMBNAIL_CONTENT_TYPE);

            String oldThumbnailPath = file.getThumbnailPath();
            file.setThumbnailPath(thumbnailPath);
            file.setUpdatedAt(LocalDateTime.now());
            fileRepository.save(file);

            if (StringUtils.hasText(oldThumbnailPath) && !oldThumbnailPath.equals(thumbnailPath)) {
                storageService.deleteObject(oldThumbnailPath);
            }
            markTaskSucceeded(task, "缩略图已生成");
        } catch (Exception e) {
            log.warn("Thumbnail generation failed, fileId={}", fileId, e);
            markTaskFailed(task, e.getMessage());
        }
    }

    private void markTaskSucceeded(TeamTaskEntity task, String resultMessage) {
        if (task != null) {
            teamTaskService.markSucceeded(task.getId(), resultMessage);
        }
    }

    private void markTaskFailed(TeamTaskEntity task, String errorMessage) {
        if (task != null) {
            teamTaskService.markFailed(task.getId(), errorMessage);
        }
    }

    private boolean canGenerate(FileEntity file) {
        if (file == null || file.getId() == null || Boolean.TRUE.equals(file.getIsDeleted())) {
            return false;
        }
        if (!"file".equals(file.getFileType()) || !StringUtils.hasText(file.getStoragePath())) {
            return false;
        }
        String mimeType = file.getMimeType();
        return StringUtils.hasText(mimeType) && mimeType.toLowerCase().startsWith("image/");
    }

    private BufferedImage resize(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        double scale = Math.min(1.0d, Math.min(
                (double) MAX_THUMBNAIL_SIDE / width,
                (double) MAX_THUMBNAIL_SIDE / height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private String thumbnailPath(FileEntity file) {
        if (file.getTeamId() != null) {
            return "thumbnails/teams/" + file.getTeamId() + "/" + file.getId() + ".jpg";
        }
        return "thumbnails/users/" + file.getUserId() + "/" + file.getId() + ".jpg";
    }
}
