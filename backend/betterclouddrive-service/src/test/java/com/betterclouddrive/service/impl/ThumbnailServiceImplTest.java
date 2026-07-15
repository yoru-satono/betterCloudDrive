package com.betterclouddrive.service.impl;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.TeamTaskEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.service.TeamTaskService;
import com.betterclouddrive.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThumbnailServiceImplTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private TeamTaskService teamTaskService;

    @InjectMocks
    private ThumbnailServiceImpl thumbnailService;

    @Test
    void generateThumbnail_shouldUploadJpegThumbnailAndSavePath() throws Exception {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .userId(7L)
                .fileType("file")
                .mimeType("image/png")
                .storagePath("objects/photo.png")
                .isDeleted(false)
                .build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageService.downloadObject("objects/photo.png")).thenReturn(new ByteArrayInputStream(pngBytes()));
        when(storageService.uploadObject(eq("thumbnails/users/7/1.jpg"), any(InputStream.class), anyLong(), eq("image/jpeg")))
                .thenAnswer(inv -> {
                    InputStream inputStream = inv.getArgument(1);
                    Long size = inv.getArgument(2);
                    assertThat(size).isPositive();
                    assertThat(inputStream.readAllBytes()).isNotEmpty();
                    return inv.getArgument(0);
                });
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        thumbnailService.generateThumbnail(1L);

        assertThat(file.getThumbnailPath()).isEqualTo("thumbnails/users/7/1.jpg");
        assertThat(file.getUpdatedAt()).isNotNull();
        verify(fileRepository).save(file);
    }

    @Test
    void generateThumbnailAsync_shouldQueueThumbnailTaskForTeamImage() {
        FileEntity file = FileEntity.builder()
                .id(2L)
                .userId(8L)
                .teamId(10L)
                .fileType("file")
                .mimeType("image/png")
                .storagePath("objects/team-photo.png")
                .isDeleted(false)
                .build();
        when(fileRepository.findById(2L)).thenReturn(Optional.of(file));
        when(teamTaskService.queueTask(10L, 8L, "THUMBNAIL", "FILE", 2L, "Generate team file thumbnail"))
                .thenReturn(TeamTaskEntity.builder().id(55L).build());

        thumbnailService.generateThumbnailAsync(2L);

        verify(teamTaskService).queueTask(10L, 8L, "THUMBNAIL", "FILE", 2L, "Generate team file thumbnail");
        verifyNoInteractions(storageService);
    }

    @Test
    void generateThumbnailAsync_shouldQueueThumbnailTaskForPersonalImage() {
        FileEntity file = FileEntity.builder()
                .id(3L)
                .userId(7L)
                .fileType("file")
                .mimeType("image/png")
                .storagePath("objects/personal-photo.png")
                .isDeleted(false)
                .build();
        when(fileRepository.findById(3L)).thenReturn(Optional.of(file));
        when(teamTaskService.queueTask(null, 7L, "THUMBNAIL", "FILE", 3L, "Generate personal file thumbnail"))
                .thenReturn(TeamTaskEntity.builder().id(56L).build());

        thumbnailService.generateThumbnailAsync(3L);

        verify(teamTaskService).queueTask(null, 7L, "THUMBNAIL", "FILE", 3L, "Generate personal file thumbnail");
        verifyNoInteractions(storageService);
    }

    @Test
    void processPendingThumbnails_shouldGenerateAndMarkTaskSucceeded() throws Exception {
        FileEntity file = FileEntity.builder()
                .id(2L)
                .teamId(10L)
                .fileType("file")
                .mimeType("image/png")
                .storagePath("objects/team-photo.png")
                .isDeleted(false)
                .build();
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(55L)
                .taskType("THUMBNAIL")
                .targetType("FILE")
                .targetId(2L)
                .build();
        when(teamTaskService.claimTasks("THUMBNAIL", 5)).thenReturn(List.of(task));
        when(fileRepository.findById(2L)).thenReturn(Optional.of(file));
        when(storageService.downloadObject("objects/team-photo.png")).thenReturn(new ByteArrayInputStream(pngBytes()));
        when(storageService.uploadObject(eq("thumbnails/teams/10/2.jpg"), any(InputStream.class), anyLong(), eq("image/jpeg")))
                .thenReturn("thumbnails/teams/10/2.jpg");
        when(fileRepository.save(any(FileEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int processed = thumbnailService.processPendingThumbnails(5);

        assertThat(processed).isEqualTo(1);
        assertThat(file.getThumbnailPath()).isEqualTo("thumbnails/teams/10/2.jpg");
        verify(teamTaskService).markSucceeded(55L, "Thumbnail generated");
    }

    @Test
    void generateThumbnail_shouldSkipUnsupportedFiles() {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .userId(7L)
                .fileType("file")
                .mimeType("text/plain")
                .storagePath("objects/readme.txt")
                .isDeleted(false)
                .build();
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));

        thumbnailService.generateThumbnail(1L);

        verifyNoInteractions(storageService);
        verify(fileRepository, never()).save(any(FileEntity.class));
    }

    @Test
    void processPendingThumbnails_shouldMarkTaskFailedWhenImageCannotBeDecoded() {
        FileEntity file = FileEntity.builder()
                .id(1L)
                .teamId(10L)
                .fileType("file")
                .mimeType("image/png")
                .storagePath("objects/broken.png")
                .isDeleted(false)
                .build();
        TeamTaskEntity task = TeamTaskEntity.builder()
                .id(56L)
                .taskType("THUMBNAIL")
                .targetType("FILE")
                .targetId(1L)
                .build();
        when(teamTaskService.claimTasks("THUMBNAIL", 5)).thenReturn(List.of(task));
        when(fileRepository.findById(1L)).thenReturn(Optional.of(file));
        when(storageService.downloadObject("objects/broken.png"))
                .thenReturn(new ByteArrayInputStream("not an image".getBytes()));

        int processed = thumbnailService.processPendingThumbnails(5);

        assertThat(processed).isEqualTo(1);
        assertThat(file.getThumbnailPath()).isNull();
        verify(storageService, never()).uploadObject(any(), any(), anyLong(), any());
        verify(fileRepository, never()).save(any(FileEntity.class));
        verify(teamTaskService).markFailed(56L, "Image cannot be decoded");
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.GREEN);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("thumbnail", 20, 40);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
